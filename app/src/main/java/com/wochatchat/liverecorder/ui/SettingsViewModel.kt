package com.wochatchat.liverecorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wochatchat.liverecorder.RecorderApp
import com.wochatchat.liverecorder.data.AppSettings
import com.wochatchat.liverecorder.data.AuthStore
import com.wochatchat.liverecorder.data.MonitorStore
import com.wochatchat.liverecorder.data.ProxySettings
import com.wochatchat.liverecorder.push.HttpPusher
import com.wochatchat.liverecorder.push.PushConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** 5a：全局录制设置（画质/循环时间/分段/命名等）。 */
    val appSettings: StateFlow<AppSettings> = (app as RecorderApp).appSettings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    /** 4b：平台 cookie（平台键 → cookie 串）。 */
    val cookies: StateFlow<Map<String, String>> = authStore.cookies
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 4b：登录平台账密（平台键 → 用户名/密码）。 */
    val credentials: StateFlow<Map<String, Pair<String, String>>> = authStore.credentials
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun setPushConfig(enabled: Boolean, type: String, api: String) = viewModelScope.launch {
        store.setPushConfig(enabled, type, api)
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
            _pushTestResult.value = "请先启用推送并填写推送地址"
            return
        }
        viewModelScope.launch {
            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val failed = (getApplication() as RecorderApp).pusher.push(
                cfg, HttpPusher.Event.LIVE, "测试主播", timeStr, "https://live.douyin.com/"
            )
            _pushTestResult.value = if (failed.isEmpty()) "测试通知已发送"
            else "发送失败：${failed.joinToString()}"
        }
    }

    fun consumePushTestResult() {
        _pushTestResult.value = null
    }
}
