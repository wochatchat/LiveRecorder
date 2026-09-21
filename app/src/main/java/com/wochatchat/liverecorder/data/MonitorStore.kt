package com.wochatchat.liverecorder.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wochatchat.liverecorder.push.PushConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "monitor")

/**
 * Monitored live URLs. Stored as one URL per line in a single string key —
 * newline-joined keeps insertion order (a StringSet would not).
 */
class MonitorStore(private val context: Context) {

    private val key = stringPreferencesKey("urls")

    /** 监控总开关（2b）：开启时服务常驻并轮询检查开播状态。 */
    private val monitorKey = booleanPreferencesKey("monitor_enabled")

    val monitorEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[monitorKey] ?: false
    }

    suspend fun setMonitorEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[monitorKey] = enabled }
    }

    val urls: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[key]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
    }

    suspend fun add(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[key]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
            if (trimmed !in current) {
                prefs[key] = (current + trimmed).joinToString("\n")
            }
        }
    }

    suspend fun remove(url: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[key]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
            prefs[key] = (current - url).joinToString("\n")
        }
    }

    /** HTTP 推送配置（2f）：类型 + 地址列表（中英文逗号分隔）+ 总开关。 */
    private val pushEnabledKey = booleanPreferencesKey("push_enabled")
    private val pushTypeKey = stringPreferencesKey("push_type")
    private val pushApiKey = stringPreferencesKey("push_api")

    val pushConfig: Flow<PushConfig> = context.dataStore.data.map { prefs ->
        PushConfig(
            enabled = prefs[pushEnabledKey] ?: false,
            type = prefs[pushTypeKey] ?: PushConfig.TYPE_NTFY,
            apis = (prefs[pushApiKey] ?: "")
                .replace('，', ',')
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() },
        )
    }

    suspend fun setPushConfig(enabled: Boolean, type: String, api: String) {
        context.dataStore.edit { prefs ->
            prefs[pushEnabledKey] = enabled
            prefs[pushTypeKey] = type
            prefs[pushApiKey] = api
        }
    }
}
