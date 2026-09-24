package com.wochatchat.liverecorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wochatchat.liverecorder.RecorderApp
import com.wochatchat.liverecorder.data.AppSettings
import com.wochatchat.liverecorder.data.AuthStore
import com.wochatchat.liverecorder.data.MonitorStore
import com.wochatchat.liverecorder.data.ProxySettings
import com.wochatchat.liverecorder.push.PushConfig
import com.wochatchat.liverecorder.recorder.RecordController
import com.wochatchat.liverecorder.service.MonitorService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    private val store = MonitorStore(app)
    private val controller = (app as RecorderApp).recordController

    val urls: StateFlow<List<String>> = store.urls
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 监控总开关（UI 切换，持久化）。 */
    val monitorEnabled: StateFlow<Boolean> = store.monitorEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** HTTP 推送配置（2f，UI 切换，持久化）。 */
    val pushConfig: StateFlow<PushConfig> = store.pushConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PushConfig())

    /** 已停用的监控条目集合（2g 单条启停，上游 # 注释语义）。 */
    val disabledUrls: StateFlow<Set<String>> = store.disabledUrls
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** 3-3h：录制完成后自动转 MP4 开关。 */
    val autoConvertMp4: StateFlow<Boolean> = store.autoConvertMp4
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 4a：代理设置。 */
    val proxySettings: StateFlow<ProxySettings> = store.proxySettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProxySettings())

    /** 5a：全局录制设置（画质/循环时间/分段/https/推送开关等）。 */
    val appSettings: StateFlow<AppSettings> = (app as RecorderApp).appSettings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val authStore = AuthStore(app)

    /** 4b：平台 cookie（平台键 → cookie 串）。 */
    val cookies: StateFlow<Map<String, String>> = authStore.cookies
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 4b：登录平台账密（平台键 → 用户名/密码）。 */
    val credentials: StateFlow<Map<String, Pair<String, String>>> = authStore.credentials
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        // 2b 接线：服务常驻条件 = 监控开启 或 有活动录制；两者皆无则停服。
        viewModelScope.launch {
            combine(store.monitorEnabled, controller.states) { enabled, states ->
                val active = states.values.any {
                    it is RecordController.RecordState.Resolving ||
                        it is RecordController.RecordState.Recording ||
                        it is RecordController.RecordState.Reconnecting
                }
                when {
                    enabled -> MonitorService.startMonitor(getApplication())
                    active -> MonitorService.start(getApplication())
                    else -> MonitorService.stop(getApplication())
                }
            }.collect { }
        }
    }

    /** url → 录制状态（Resolving/Recording/Finished/Failed）。 */
    val recordStates: StateFlow<Map<String, RecordController.RecordState>> = controller.states

    /** url → 监控状态（Unknown/Live/Offline/Error），供 UI 状态徽标。 */
    val monitorStates get() = (getApplication() as RecorderApp).monitorLoop.states

    /** 4c：不健康条目集合（连续检查失败 ≥3 轮），UI 置灰「失效」徽标。 */
    val unhealthyUrls get() = (getApplication() as RecorderApp).monitorLoop.unhealthy

    fun setMonitorEnabled(enabled: Boolean) = viewModelScope.launch {
        store.setMonitorEnabled(enabled)
        // 状态由 init 的 combine 驱动服务启停，这里无需重复调用
    }

    /** 保存推送配置（2f）。 */
    fun setPushConfig(enabled: Boolean, type: String, api: String) = viewModelScope.launch {
        store.setPushConfig(enabled, type, api)
    }

    /** 3-3h：切换录制完成后自动转 MP4。 */
    fun setAutoConvertMp4(enabled: Boolean) = viewModelScope.launch {
        store.setAutoConvertMp4(enabled)
    }

    /** 4a：保存代理设置。 */
    fun setProxySettings(settings: ProxySettings) = viewModelScope.launch {
        store.setProxySettings(settings)
    }

    /** 5a：保存全局录制设置。 */
    fun setAppSettings(settings: AppSettings) = viewModelScope.launch {
        (app as RecorderApp).appSettings.set(settings)
    }

    /** 4b：保存/清除平台 cookie。 */
    fun setCookie(platform: String, cookie: String) = viewModelScope.launch {
        authStore.setCookie(platform, cookie)
    }

    /** 4b：保存登录平台账密。 */
    fun setCredential(platform: String, username: String, password: String) = viewModelScope.launch {
        authStore.setCredential(platform, username, password)
    }

        fun add(url: String) = viewModelScope.launch { store.add(url) }

    fun remove(url: String) = viewModelScope.launch {
        controller.stop(url)
        (getApplication() as RecorderApp).monitorLoop.forget(url)
        store.remove(url)
    }

    /** 编辑/重命名 URL（2g）。 */
    fun renameUrl(oldUrl: String, newUrl: String) = viewModelScope.launch {
        controller.stop(oldUrl)
        (getApplication() as RecorderApp).monitorLoop.forget(oldUrl)
        store.renameUrl(oldUrl, newUrl)
    }

    /** 单条启停（2g）：false=停用（上游 # 注释行），true=启用参与轮询。 */
    fun setEnabled(url: String, enabled: Boolean) = viewModelScope.launch {
        store.setEnabled(url, enabled)
    }

    fun startRecord(url: String) = controller.start(url)

    fun stopRecord(url: String) {
        controller.stop(url)
        // 手动停止后不再自动重启录制，直到该房间转为未开播
        (getApplication() as RecorderApp).monitorLoop.suppressAutoStart(url)
    }
}

