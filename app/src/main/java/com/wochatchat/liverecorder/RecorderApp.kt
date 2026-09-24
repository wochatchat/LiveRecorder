package com.wochatchat.liverecorder

import android.app.Application
import android.util.Log
import com.wochatchat.liverecorder.monitor.MonitorLoop
import com.wochatchat.liverecorder.platform.PlatformRouter
import com.wochatchat.liverecorder.platform.douyin.DouyinSpider
import com.wochatchat.liverecorder.platform.douyu.DouyuSpider
import com.wochatchat.liverecorder.recorder.FfmpegRecorder
import com.wochatchat.liverecorder.recorder.RecordController
import com.wochatchat.liverecorder.data.AuthStore
import com.wochatchat.liverecorder.data.AppSettings
import com.wochatchat.liverecorder.data.AppSettingsStore
import com.wochatchat.liverecorder.data.MonitorStore
import com.wochatchat.liverecorder.recorder.RecordSource
import com.wochatchat.liverecorder.push.HttpPusher
import com.wochatchat.liverecorder.service.EventNotifier
import com.wochatchat.liverecorder.storage.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 进程级录制控制器（应用销毁前常驻，独立于 Activity 生命周期）。 */
class RecorderApp : Application() {

    lateinit var recordController: RecordController
        private set

    lateinit var monitorLoop: MonitorLoop
        private set

    lateinit var pusher: HttpPusher
        private set

    /** 保存目录存储检查（2h）：低于阈值暂停监控录制并通知。 */
    lateinit var storage: StorageManager
        private set

    /** App 级后台任务域（推送等 fire-and-forget 工作）。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val store by lazy { MonitorStore(this) }

    /** 4b：平台 cookie / 账密（快手等平台爬虫按需取用）。 */
    private val authStore by lazy { AuthStore(this) }

    /** 5a：全局录制设置（画质/循环时间/分段/https/推送开关等）。 */
    val appSettings by lazy { AppSettingsStore(this) }

    private fun timeNow(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    override fun onCreate() {
        super.onCreate()
        // 事件渠道尽早创建（2e：开播/关播通知）
        val notifier = EventNotifier(this)
        notifier.createChannel()
        val spider = DouyinSpider()
        val router = PlatformRouter(spider, DouyuSpider())
        val pusher = HttpPusher()
        // 3g：ffmpeg 分段录制（m3u8 必须 + FLV 分段）
        val ffmpegBin = File(applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val ffmpegRecorder = if (ffmpegBin.exists()) {
            FfmpegRecorder(ffmpegBin = ffmpegBin, scope = appScope)
        } else null
        recordController = RecordController(
            baseDir = File(filesDir, "downloads"),
            fetchInfo = { url, proxyAddr ->
                val settings = appSettings.settings.first()
                router.fetchStreamInfo(
                    url,
                    quality = RecordSource.getQualityCode(settings.quality),
                    proxyAddr = proxyAddr,
                    cookies = authStore.cookies.first(),
                )
            },
            ffmpeg = ffmpegRecorder,
            // 3-3h：录制完成后自动转 MP4（开关持久化在 MonitorStore）
            mp4Convert = { store.autoConvertMp4.first() },
            // 4a：per-platform 代理（use_proxy + 平台关键词匹配，对齐上游 main.py:558-573）
            resolveProxy = { url -> store.proxySettings.first().resolveProxy(url) },
            // 5a：全局录制配置（分段开关/分段时间/强制 https/转码删原文件）
            useSegmented = { appSettings.settings.first().segmented },
            segmentTimeSec = { appSettings.settings.first().segmentTimeSec },
            forceHttps = { appSettings.settings.first().forceHttps },
            deleteOriginalOnConvert = { appSettings.settings.first().deleteOriginalOnConvert },
        )
        monitorLoop = MonitorLoop(
            check = { url ->
                // 轮询探测与录制同源走同一代理判定（上游 check/record 共用 proxy_address）
                val settings = appSettings.settings.first()
                router.fetchStreamInfo(
                    url,
                    quality = RecordSource.getQualityCode(settings.quality),
                    proxyAddr = store.proxySettings.first().resolveProxy(url),
                    cookies = authStore.cookies.first(),
                )
            },
            isRecording = { url -> recordController.isActive(url) },
            onLive = { url, _ ->
                // 5a：只推送通知不录制（上游 disable_record）
                appScope.launch {
                    if (runCatching { appSettings.settings.first().onlyNotify }.getOrDefault(false)) {
                        Log.i("RecorderApp", "只推送不录制: $url")
                    } else {
                        recordController.start(url)
                    }
                }
            },
            onLiveEvent = { url, anchor, title ->
                notifier.notifyLive(url, anchor, title)
                // 2f：HTTP 推送（ntfy/bark），fire-and-forget，配置未启用则内部跳过
                appScope.launch {
                    val cfg = store.pushConfig.first()
                    // 5a：开播推送开关（上游「开播推送开启」，默认是）
                    if (cfg.isValid && runCatching { appSettings.settings.first().pushOnLive }.getOrDefault(true)) {
                        pusher.pushLiveAsync(cfg, anchor, timeNow(), liveUrl = url)
                    }
                }
            },
            onOfflineEvent = { url, anchor ->
                notifier.notifyOffline(url, anchor)
                appScope.launch {
                    val cfg = store.pushConfig.first()
                    // 5a：关播推送开关（上游「关播推送开启」，默认否）
                    if (cfg.isValid && runCatching { appSettings.settings.first().pushOnOffline }.getOrDefault(false)) {
                        pusher.pushOfflineAsync(cfg, anchor, timeNow(), liveUrl = url)
                    }
                }
            },
            // 2h 存储阈值：低于阈值暂停轮询 + 停掉活动录制 + 通知；恢复后自动继续
            storageOk = {
                !storage.isLow(store.diskLimitGb.first())
            },
            onLowStorage = {
                // 回调非 suspend：切 appScope 取阈值后再通知
                appScope.launch {
                    val limit = store.diskLimitGb.first()
                    notifier.notifyStorageLow(limit, storage.freeGb())
                    recordController.stopAll()
                }
            },
            onStorageResumed = { notifier.notifyStorageResumed() },
        )
        this.pusher = pusher
        this.storage = StorageManager(File(filesDir, "downloads"))
    }
}
