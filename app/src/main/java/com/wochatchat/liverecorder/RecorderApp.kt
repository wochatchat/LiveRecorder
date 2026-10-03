package com.wochatchat.liverecorder

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import com.wochatchat.liverecorder.data.AppLog
import com.wochatchat.liverecorder.monitor.MonitorLoop
import com.wochatchat.liverecorder.platform.PlatformRouter
import com.wochatchat.liverecorder.platform.douyin.DouyinSpider
import com.wochatchat.liverecorder.platform.douyu.DouyuSpider
import com.wochatchat.liverecorder.recorder.FfmpegRecorder
import com.wochatchat.liverecorder.recorder.RecordController
import com.wochatchat.liverecorder.data.AuthStore
import com.wochatchat.liverecorder.data.AccountHealth
import com.wochatchat.liverecorder.data.AppSettings
import com.wochatchat.liverecorder.data.AppSettingsStore
import com.wochatchat.liverecorder.data.MonitorStore
import com.wochatchat.liverecorder.data.RecordHistoryEntry
import com.wochatchat.liverecorder.data.RecordHistoryStore
import com.wochatchat.liverecorder.recorder.RecordSource
import com.wochatchat.liverecorder.push.HttpPusher
import com.wochatchat.liverecorder.service.EventNotifier
import com.wochatchat.liverecorder.storage.StorageManager
import com.wochatchat.liverecorder.ui.components.platformKeyForUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
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

    /** Phase 4-4.4：当前是否连接 WiFi（省电 wifiOnly 模式判定）。 */
    private fun isOnWifi(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /** Phase 4-4.4：屏幕是否亮起。 */
    private fun isScreenOn(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    private val store by lazy { MonitorStore(this) }

    /** 4b：平台 cookie / 账密（快手等平台爬虫按需取用）。 */
    private val authStore by lazy { AuthStore(this) }

    /** 5a：全局录制设置（画质/循环时间/分段/https/推送开关等）。 */
    val appSettings by lazy { AppSettingsStore(this) }

    /** 6e R18：录制历史存储层（RecordController onFinished 落库，RecordsViewModel 读取）。 */
    val historyStore by lazy { RecordHistoryStore(this) }

    private fun timeNow(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    override fun onCreate() {
        super.onCreate()
        // 5d：文件日志尽早初始化（logs/ 落 app 私有目录，日志页可查看/导出）
        AppLog.init(File(filesDir, "logs"))
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
                // Phase 4-4.1：单条画质覆盖（无覆盖回落全局画质）
                val quality = runCatching { store.getPerUrlSettings(url) }
                    .getOrNull()?.quality ?: settings.quality
                router.fetchStreamInfo(
                    url,
                    quality = RecordSource.getQualityCode(quality),
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
            // 7c：保存格式（ts 分段默认 / mkv|mp4 直存单文件）
            saveFormat = { appSettings.settings.first().saveFormat },
            forceHttps = { appSettings.settings.first().forceHttps },
            deleteOriginalOnConvert = { appSettings.settings.first().deleteOriginalOnConvert },
            // 5b：文件命名规则（作者/时间/标题区分、文件名含标题、去表情）
            namingOptions = {
                val s = appSettings.settings.first()
                RecordSource.NamingOptions(
                    folderByAuthor = s.folderByAuthor,
                    folderByTime = s.folderByTime,
                    folderByTitle = s.folderByTitle,
                    filenameByTitle = s.filenameByTitle,
                    cleanEmoji = s.cleanEmoji,
                )
            },
            // 6e R18：录制结束落库（确有落盘文件才触发，见 setState 钩子）
            onFinished = { url, fin ->
                historyStore.add(
                    RecordHistoryEntry(
                        url = url,
                        platform = fin.platform,
                        anchorName = fin.anchorName,
                        title = fin.title,
                        savePath = fin.savePath,
                        endTimeMs = System.currentTimeMillis(),
                        durationMs = fin.durationMs,
                        bytes = fin.bytes,
                        completed = fin.completed,
                    )
                )
            },
            // Phase 4-4.1：单条录制参数覆盖（fetchInfo 里已处理 quality，此处处理分段/格式）
            perUrlSettings = { url ->
                runCatching { store.getPerUrlSettings(url) }.getOrNull()
            },
        )
        monitorLoop = MonitorLoop(
            check = { url ->
                // 轮询探测与录制同源走同一代理判定（上游 check/record 共用 proxy_address）
                val settings = appSettings.settings.first()
                // Phase 4-4.1：单条画质覆盖（探测也用该条目的画质，确保录制/探测一致）
                val quality = runCatching { store.getPerUrlSettings(url) }
                    .getOrNull()?.quality ?: settings.quality
                router.fetchStreamInfo(
                    url,
                    quality = RecordSource.getQualityCode(quality),
                    proxyAddr = store.proxySettings.first().resolveProxy(url),
                    cookies = authStore.cookies.first(),
                )
            },
            isRecording = { url -> recordController.isActive(url) },
            onLive = { url, _ ->
                // 5a：只推送通知不录制（上游 disable_record）
                appScope.launch {
                    if (runCatching { appSettings.settings.first().onlyNotify }.getOrDefault(false)) {
                        AppLog.i("RecorderApp", "只推送不录制: $url")
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
            // Phase 4-4.2：定时监控（appSettings.scheduleMonitorEnabled + 当前分钟数）
            scheduleOk = {
                val s = appSettings.settings.first()
                appSettings.isWithinSchedule(s,
                    Calendar.getInstance().get(Calendar.HOUR_OF_DAY) * 60 +
                        Calendar.getInstance().get(Calendar.MINUTE)
                )
            },
            // Phase 4-4.4：WiFi-only 省电模式（wifiOnly 开启时非 WiFi 返回 false）
            networkOk = {
                val s = appSettings.settings.first()
                !s.wifiOnly || isOnWifi()
            },
            // Phase 4-4.4：熄屏暂停（screenOffPause 开启时检测屏幕状态）
            screenOnOk = {
                val s = appSettings.settings.first()
                !s.screenOffPause || isScreenOn()
            },
            // Phase 5-5.1：归集检查结果到平台健康仪表盘（环形缓冲 100 条/平台）
            onCheckResult = { url, ok ->
                val platform = platformKeyForUrl(url)
                // Phase 5-5.2：检查成功 → 账号健康恢复 ok（内部幂等，未变化不写盘）
                if (ok) authStore.markAccountHealth(platform, AccountHealth.STATUS_OK)
                store.recordCheckResult(platform, ok)
            },
        )
        this.pusher = pusher
        this.storage = StorageManager(File(filesDir, "downloads"))

        // Phase 5-5.2：账号健康度——已配置 Cookie 的平台出现不健康条目（连续检查
        // 失败 ≥3 轮）→ 标记 expired 并提醒续期；条目恢复健康 → 恢复 ok
        appScope.launch {
            var prevExpired = emptySet<String>()
            monitorLoop.unhealthy.collect { urls ->
                runCatching {
                    val cookies = authStore.cookies.first()
                    val expired = urls.map { platformKeyForUrl(it) }
                        .filter { cookies[it]?.isNotBlank() == true }
                        .toSet()
                    (expired - prevExpired).forEach { p ->
                        authStore.markAccountHealth(p, AccountHealth.STATUS_EXPIRED)
                        notifier.notifyAccountExpired(AuthStore.labelOf(p))
                    }
                    (prevExpired - expired).forEach { p ->
                        authStore.markAccountHealth(p, AccountHealth.STATUS_OK)
                    }
                    prevExpired = expired
                }
            }
        }
    }
}
