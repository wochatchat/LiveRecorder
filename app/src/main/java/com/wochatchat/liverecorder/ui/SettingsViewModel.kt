package com.wochatchat.liverecorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.RecorderApp
import com.wochatchat.liverecorder.data.AppSettings
import com.wochatchat.liverecorder.data.AuthStore
import com.wochatchat.liverecorder.data.CheckResultEntry
import com.wochatchat.liverecorder.data.ConfigExporter
import com.wochatchat.liverecorder.data.MonitorStore
import com.wochatchat.liverecorder.data.ProxySettings
import com.wochatchat.liverecorder.push.Event
import com.wochatchat.liverecorder.push.HttpPusher
import com.wochatchat.liverecorder.push.PushConfig
import com.wochatchat.liverecorder.storage.StorageUsage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页 ViewModel（6d R15）：与 MonitorViewModel 共享同一批 Store，
 * 但不携带监控服务接线（init 的 combine），设置 Tab 独立实例安全。
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val store = MonitorStore(app)
    private val authStore = AuthStore(app)

    /** HTTP 推送配置（2f）。 */
    val pushConfig: StateFlow<PushConfig> = store.pushConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PushConfig())

    /** 4a：代理设置。 */
    val proxySettings: StateFlow<ProxySettings> = store.proxySettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProxySettings())

    /** 3-3h：录制完成后自动转 MP4。 */
    val autoConvertMp4: StateFlow<Boolean> = store.autoConvertMp4
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Phase 5-5.1：平台检查结果历史（平台健康仪表盘）。 */
    val checkResultHistory: StateFlow<Map<String, List<CheckResultEntry>>> = store.checkResultHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // ---- R20：存储管理 ----

    /** 存储告警阈值（GB）（上游「录制空间剩余阈值(gb)」，触底暂停监控录制）。 */
    val diskLimitGb: StateFlow<Double> = store.diskLimitGb
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0)

    /** 存储用量（保存目录分区），30s 轮询刷新。 */
    val storageUsage: StateFlow<StorageUsage> = flow {
        while (true) {
            val storage = (getApplication() as RecorderApp).storage
            emit(StorageUsage(freeGb = storage.freeGb(), totalGb = storage.totalGb()))
            delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StorageUsage(0.0, 0.0))

    fun setDiskLimitGb(gb: Double) = viewModelScope.launch {
        store.setDiskLimitGb(gb)
    }

    /** 5a：全局录制设置（画质/循环时间/分段/命名等）。 */
    val appSettings: StateFlow<AppSettings> = (app as RecorderApp).appSettings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    /** 4b：平台 cookie（平台键 → cookie 串）。 */
    val cookies: StateFlow<Map<String, String>> = authStore.cookies
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 4b：登录平台账密（平台键 → 用户名/密码）。 */
    val credentials: StateFlow<Map<String, Pair<String, String>>> = authStore.credentials
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 明细参数带默认值：既有 3 参调用点（开关/类型/地址）不受影响。 */
    fun setPushConfig(
        enabled: Boolean,
        type: String,
        api: String,
        title: String = "",
        liveMessage: String = "",
        offlineMessage: String = "",
        barkLevel: String = "",
        barkSound: String = "",
        ntfyTags: String = "",
        ntfyPriority: Int = 0,
    ) = viewModelScope.launch {
        store.setPushConfig(enabled, type, api, title, liveMessage, offlineMessage, barkLevel, barkSound, ntfyTags, ntfyPriority)
    }

    fun setProxySettings(settings: ProxySettings) = viewModelScope.launch {
        store.setProxySettings(settings)
    }

    fun setAutoConvertMp4(enabled: Boolean) = viewModelScope.launch {
        store.setAutoConvertMp4(enabled)
    }

    fun setAppSettings(settings: AppSettings) = viewModelScope.launch {
        (getApplication() as RecorderApp).appSettings.set(settings)
    }

    fun setCookie(platform: String, cookie: String) = viewModelScope.launch {
        authStore.setCookie(platform, cookie)
    }

    fun setCredential(platform: String, username: String, password: String) = viewModelScope.launch {
        authStore.setCredential(platform, username, password)
    }

    // ---- R17：推送测试 ----

    private val _pushTestResult = MutableStateFlow<String?>(null)

    /** 测试推送结果（一次性，UI 消费后调 [consumePushTestResult] 清除）。 */
    val pushTestResult: StateFlow<String?> = _pushTestResult.asStateFlow()

    /** 发送测试通知（硬编码测试文案，走真实 HttpPusher 链路）。 */
    fun sendTestPush() {
        val cfg = pushConfig.value
        if (!cfg.isValid) {
            _pushTestResult.value = getApplication<Application>().getString(R.string.settings_push_test_need_config)
            return
        }
        viewModelScope.launch {
            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val failed = (getApplication() as RecorderApp).pusher.push(
                cfg, Event.LIVE, getApplication<Application>().getString(R.string.push_test_anchor), timeStr, "https://live.douyin.com/"
            )
            _pushTestResult.value = if (failed.isEmpty()) getApplication<Application>().getString(R.string.push_test_sent)
            else getApplication<Application>().getString(R.string.push_test_failed, failed.joinToString())
        }
    }

    fun consumePushTestResult() {
        _pushTestResult.value = null
    }

    // ---- Phase 4-4.3：配置导出/导入 ----

    private val _configOpResult = MutableStateFlow<String?>(null)

    /** 配置导入/导出操作结果（一次性，UI 消费后清除）。 */
    val configOpResult: StateFlow<String?> = _configOpResult.asStateFlow()

    fun consumeConfigOpResult() {
        _configOpResult.value = null
    }

    /** 导出全量配置到 [output]（调用方已打开 SAF URI 流；[includeAuth] false 则不含 cookie/账密）。 */
    fun exportConfig(output: java.io.OutputStream, includeAuth: Boolean) {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val json = ConfigExporter.exportAll(
                    appSettings = (app as RecorderApp).appSettings.settings.first(),
                    proxySettings = store.proxySettings.first(),
                    pushConfig = store.pushConfig.first(),
                    urls = store.urls.first(),
                    disabledUrls = store.disabledUrls.first(),
                    perUrlOverrides = store.perUrlOverrides.first(),
                    authData = if (includeAuth) ConfigExporter.AuthData(
                        cookies = authStore.cookies.first(),
                        credentials = authStore.credentials.first(),
                    ) else null,
                )
                output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                _configOpResult.value = app.getString(R.string.config_export_done)
            } catch (e: Exception) {
                _configOpResult.value = getApplication<Application>().getString(
                    R.string.config_op_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** 从 [input]（SAF URI 流）导入配置。[passwordConfirmed] 表示用户已确认账密明文导入。 */
    fun importConfig(input: java.io.InputStream, passwordConfirmed: Boolean = false) {
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val json = input.use { it.readBytes().toString(Charsets.UTF_8) }
                when (val r = ConfigExporter.parseImport(json, passwordConfirmed)) {
                    is ConfigExporter.ImportResult.Error ->
                        _configOpResult.value = app.getString(R.string.config_op_failed, r.message)
                    ConfigExporter.ImportResult.NeedsPasswordConfirmation ->
                        _configOpResult.value = NEEDS_PASSWORD_CONFIRMATION
                    is ConfigExporter.ImportResult.Success -> applyImport(r, app)
                }
            } catch (e: Exception) {
                _configOpResult.value = getApplication<Application>().getString(
                    R.string.config_op_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun applyImport(r: ConfigExporter.ImportResult.Success, app: Application) {
        // 1) 监控条目：整体替换
        store.urls.first().forEach { store.remove(it) }
        r.urls.forEach { store.add(it) }
        r.disabledUrls.forEach { store.setEnabled(it, false) }
        // 2) 单条覆盖：先清除再写入
        store.perUrlOverrides.first().keys.forEach { store.setPerUrlSettings(it, null) }
        r.perUrlOverrides.forEach { (url, s) -> store.setPerUrlSettings(url, s) }
        // 3) 代理/推送
        store.setProxySettings(r.proxySettings)
        store.setPushConfig(
            enabled = r.pushConfig.enabled, type = r.pushConfig.type,
            api = r.pushConfig.apis.joinToString(","),
            title = r.pushConfig.title, liveMessage = r.pushConfig.liveMessage,
            offlineMessage = r.pushConfig.offlineMessage, barkLevel = r.pushConfig.barkLevel,
            barkSound = r.pushConfig.barkSound, ntfyTags = r.pushConfig.ntfyTags,
            ntfyPriority = r.pushConfig.ntfyPriority,
        )
        // 4) 全局录制设置
        (app as RecorderApp).appSettings.set(r.appSettings)
        // 5) 认证数据（parseImport 已处理二次确认）
        r.cookies.forEach { (platform, cookie) -> authStore.setCookie(platform, cookie) }
        r.credentials.forEach { (platform, pair) ->
            authStore.setCredential(platform, pair.first, pair.second)
        }
        _configOpResult.value = app.getString(R.string.config_import_done)
    }

    companion object {
        /** 导入含敏感数据时的哨兵值（UI 据此弹二次确认对话框）。 */
        const val NEEDS_PASSWORD_CONFIRMATION = "__NEEDS_PASSWORD_CONFIRMATION__"
    }
}
