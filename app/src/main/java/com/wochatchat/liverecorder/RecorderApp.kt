package com.wochatchat.liverecorder

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
import com.wochatchat.liverecorder.data.AdaptiveQuality
import com.wochatchat.liverecorder.data.AppSettings
import com.wochatchat.liverecorder.data.CloudSyncStore
import com.wochatchat.liverecorder.data.NetType
import com.wochatchat.liverecorder.data.AppSettingsStore
import com.wochatchat.liverecorder.data.MonitorStore
import com.wochatchat.liverecorder.data.Accounts
import com.wochatchat.liverecorder.data.RecordHistoryEntry
import com.wochatchat.liverecorder.data.RecordHistoryStore
import com.wochatchat.liverecorder.data.GhostRecovery
import com.wochatchat.liverecorder.recorder.RecordSource
import com.wochatchat.liverecorder.push.HttpPusher
import com.wochatchat.liverecorder.service.EventNotifier
import com.wochatchat.liverecorder.storage.StorageManager
import com.wochatchat.liverecorder.sync.CloudSyncManager
import com.wochatchat.liverecorder.ui.components.platformKeyForUrl
import com.wochatchat.liverecorder.data.DailyReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** Phase 10-10.2：当前网络类型流（自适应画质用），NetworkCallback 实时更新。 */
    private val netType = MutableStateFlow(NetType.NONE)

    /** Phase 10-10.2：注册默认网络回调，跟踪 WiFi/流量切换（失败只记日志）。 */
    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    netType.value = when {
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetType.WIFI
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetType.CELLULAR
                        else -> NetType.NONE
                    }
                }

                override fun onLost(network: Network) {
                    netType.value = NetType.NONE
                }
            })
        } catch (e: Exception) {
            AppLog.e("RecorderApp", "网络回调注册失败: ${e.message}")
        }
    }

    /**
     * Phase 11-11.2：探测/录制共用的实际 cookie 集（条目绑定账号优先，未绑定/账号已删
     * 回落默认账号 = 既有单 cookie）。
     */
    suspend fun effectiveCookies(url: String): Map<String, String> {
        val legacy = authStore.cookies.first()
        val binding = runCatching { store.getAccountBinding(url) }.getOrDefault("")
        if (binding.isBlank() || binding == Accounts.DEFAULT_ID) return legacy
        val platform = Accounts.cookieKeyForPlatform(platformKeyForUrl(url))
        val cookie = Accounts.resolveCookie(platform, binding, authStore.accounts.first(), legacy)
        return if (cookie != null) legacy + (platform to cookie) else legacy
    }

    /**
     * Phase 10-10.2：探测/录制共用的实际画质（单条画质覆盖优先，其次自适应，最后全局）。
     */
    private suspend fun effectiveQuality(url: String): String {
        val settings = appSettings.settings.first()
        val base = runCatching { store.getPerUrlSettings(url) }.getOrNull()?.quality
            ?: settings.quality
        return if (settings.adaptiveQuality) {
            AdaptiveQuality.adaptive(base, netType.value)
        } else base
    }

    private val store by lazy { MonitorStore(this) }

    /** 4b：平台 cookie / 账密（快手等平台爬虫按需取用）。 */
    private val authStore by lazy { AuthStore(this) }

    /** 5a：全局录制设置（画质/循环时间/分段/https/推送开关等）。 */
    val appSettings by lazy { AppSettingsStore(this) }

    /** 6e R18：录制历史存储层（RecordController onFinished 落库，RecordsViewModel 读取）。 */
    val historyStore by lazy { RecordHistoryStore(this) }

    /** Phase 11-11.1：WebDAV 云同步（录制完成后自动上传 NAS）。 */
    val cloudSyncManager by lazy {
        CloudSyncManager(
            store = cloudSyncStore,
            baseDir = File(filesDir, "downloads"),
            savePaths = { historyStore.entries.first().map { it.savePath } },
        )
    }

    /** Phase 11-11.1：云同步设置存储。 */
    val cloudSyncStore by lazy { CloudSyncStore(this) }

    /**
     * V3-1：幽灵文件回收——扫描 downloads/ 下未入库的完整视频文件，按目录聚合
     * 补写历史（completed=false，时间取 mtime）。App 启动与记录页进入时调用。
     */
    suspend fun recoverGhostFiles(): Int {
        val base = File(filesDir, "downloads")
        val known = historyStore.entries.first().map { it.savePath }.toSet()
        val ghosts = GhostRecovery.findGhosts(base, known, System.currentTimeMillis())
        if (ghosts.isEmpty()) return 0
        ghosts.forEach { g ->
            historyStore.add(
                RecordHistoryEntry(
                    url = "",
                    platform = "",
                    anchorName = "",
                    title = g.displayName,
                    savePath = g.dirPath,
                    endTimeMs = g.lastModifiedMs,
                    durationMs = 0,
                    bytes = g.bytes,
                    completed = false,
                )
            )
            AppLog.w("GhostRecovery", "回收未入库文件(补写历史): ${g.dirPath} bytes=${g.bytes} files=${g.files.size}")
        }
        return ghosts.size
    }

    private fun timeNow(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    override fun onCreate() {
        super.onCreate()
        // Phase 10-10.2：注册网络回调（自适应画质感知 WiFi/流量切换）
        registerNetworkCallback()
        // Phase 11-11.1：云同步常驻循环（开关关闭时 syncOnce 直接空转返回）
        appScope.launch { cloudSyncManager.runLoop() }
        // V3-1：启动时幽灵文件回收——未入库的落盘视频补写历史（completed=false）
        appScope.launch { runCatching { recoverGhostFiles() } }
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
                // Phase 10-10.2：画质统一走 effectiveQuality（单条覆盖 → 自适应 → 全局）
                val quality = effectiveQuality(url)
                router.fetchStreamInfo(
                    url,
                    quality = RecordSource.getQualityCode(quality),
                    proxyAddr = proxyAddr,
                    cookies = effectiveCookies(url),
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
                // Phase 11-11.1：录制完成立即触发一次云同步扫描
                runCatching { cloudSyncManager.kickUpload() }
            },
            // V3-1 R2：录制失败通知（开录后的失败不再静默；开关默认开）
            onFailed = { url, fail ->
                if (appSettings.settings.first().recordFailureNotify) {
                    notifier.notifyRecordFailed(url, fail.message)
                }
            },
            // Phase 4-4.1：单条录制参数覆盖（fetchInfo 里已处理 quality，此处处理分段/格式）
            perUrlSettings = { url ->
                runCatching { store.getPerUrlSettings(url) }.getOrNull()
            },
        )
        monitorLoop = MonitorLoop(
            check = { url ->
                // 轮询探测与录制同源走同一代理判定（上游 check/record 共用 proxy_address）
                // Phase 10-10.2：画质统一走 effectiveQuality（单条覆盖 → 自适应 → 全局）
                val quality = effectiveQuality(url)
                router.fetchStreamInfo(
                    url,
                    quality = RecordSource.getQualityCode(quality),
                    proxyAddr = store.proxySettings.first().resolveProxy(url),
                    cookies = effectiveCookies(url),
                )
            },
            isRecording = { url -> recordController.isActive(url) },
            onLive = { url, _ ->
                // 5a：只推送通知不录制（上游 disable_record）
                appScope.launch {
                    if (runCatching { appSettings.settings.first().onlyNotify }.getOrDefault(false)) {
                        AppLog.i("RecorderApp", "只推送不录制: $url")
                    } else {
                        recordController.startFromMonitor(url)
                    }
                }
            },
            onLiveEvent = { url, anchor, title ->
                appScope.launch {
                    val s = runCatching { appSettings.settings.first() }.getOrNull()
                    // Phase 6-6.1：静音时段（23-07）不发开播提醒；HTTP 推送同属打扰一并跳过
                    val quiet = s != null && AppSettings.isQuietHour(s, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
                    if (quiet) {
                        AppLog.i("RecorderApp", "静音时段：跳过开播通知 $url")
                    } else {
                        notifier.notifyLive(url, anchor, title)
                    }
                    // 2f：HTTP 推送（ntfy/bark），fire-and-forget，配置未启用则内部跳过
                    val cfg = store.pushConfig.first()
                    // 5a：开播推送开关（上游「开播推送开启」，默认是）
                    if (!quiet && cfg.isValid && runCatching { appSettings.settings.first().pushOnLive }.getOrDefault(true)) {
                        pusher.pushLiveAsync(cfg, anchor, timeNow(), liveUrl = url)
                    }
                }
            },
            onOfflineEvent = { url, anchor ->
                appScope.launch {
                    val s = runCatching { appSettings.settings.first() }.getOrNull()
                    val quiet = s != null && AppSettings.isQuietHour(s, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
                    if (quiet) {
                        AppLog.i("RecorderApp", "静音时段：跳过关播通知 $url")
                    } else {
                        notifier.notifyOffline(url, anchor)
                    }
                    val cfg = store.pushConfig.first()
                    // 5a：关播推送开关（上游「关播推送开启」，默认否）
                    if (!quiet && cfg.isValid && runCatching { appSettings.settings.first().pushOnOffline }.getOrDefault(false)) {
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

        // Phase 8-8.1：录制日报——每日 09:00 后推送昨日统计（走 HTTP 推送）。
        // 依赖监控前台服务保活进程；每 15 分钟轮询检查一次，当天已发不重复。
        appScope.launch {
            while (true) {
                runCatching {
                    val s = appSettings.settings.first()
                    if (s.dailyReportEnabled) {
                        val today = DailyReport.dayStartOf(System.currentTimeMillis())
                        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                            .format(java.util.Date(today))
                        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                        if (appSettings.lastDailyReportDate.first() != todayStr && hour >= 9) { // 每日 09:00 后发昨日日报
                            sendDailyReport(today - DailyReport.DAY_MS)
                            appSettings.setLastDailyReportDate(todayStr)
                            AppLog.i("RecorderApp", "录制日报已发送")
                        }
                    }
                }.onFailure { AppLog.w("RecorderApp", "日报调度异常: ${it.message}") }
                kotlinx.coroutines.delay(15 * 60_000L)
            }
        }
    }

    /** Phase 8-8.1：发送 [dayStartMs] 当天（本地日）的录制日报文本推送。 */
    private suspend fun sendDailyReport(dayStartMs: Long) {
        val cfg = store.pushConfig.first()
        if (!cfg.isValid) return
        val stats = DailyReport.statsForDay(historyStore.entries.first(), dayStartMs)
        val day = java.text.SimpleDateFormat("MM-dd", java.util.Locale.US).format(java.util.Date(dayStartMs))
        val content = buildString {
            append("场次 ${stats.sessions} · 成功率 ${(stats.successRate * 100).toInt()}%")
            if (stats.sessions > 0) {
                append("\n总时长 ")
                append(com.wochatchat.liverecorder.ui.StatsFormat.duration(stats.totalDurationMs))
                append(" · 总大小 ")
                append(com.wochatchat.liverecorder.ui.StatsFormat.bytes(stats.totalBytes))
            }
        }
        pusher.pushTextAsync(cfg, getString(com.wochatchat.liverecorder.R.string.daily_report_title, day), content)
    }
}
