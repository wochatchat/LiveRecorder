package com.wochatchat.liverecorder.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.cloudSyncDataStore by preferencesDataStore(name = "cloud_sync")

/**
 * Phase 11-11.1：云同步（WebDAV）设置。
 * 录制完成后自动上传 NAS；失败按指数退避重试；可选上传后保留本地副本。
 */
data class CloudSyncSettings(
    /** 云同步开关。 */
    val enabled: Boolean = false,
    /** WebDAV 服务器地址（如 https://nas.local:5006/）。 */
    val serverUrl: String = "",
    /** 用户名（可空=匿名）。 */
    val username: String = "",
    /** 密码。 */
    val password: String = "",
    /** 远端根目录（相对 serverUrl，默认 LiveRecorder）。 */
    val remoteDir: String = "LiveRecorder",
    /** 上传成功后是否保留本地副本（默认保留）。 */
    val keepLocal: Boolean = true,
)

/** 云同步设置持久化（独立 "cloud_sync" DataStore，凭据不进 git/配置导出）。 */
class CloudSyncStore(private val context: android.content.Context) {

    private val enabledKey = booleanPreferencesKey("enabled")
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val usernameKey = stringPreferencesKey("username")
    private val passwordKey = stringPreferencesKey("password")
    private val remoteDirKey = stringPreferencesKey("remote_dir")
    private val keepLocalKey = booleanPreferencesKey("keep_local")

    val settings: Flow<CloudSyncSettings> = context.cloudSyncDataStore.data.map { prefs ->
        CloudSyncSettings(
            enabled = prefs[enabledKey] ?: false,
            serverUrl = prefs[serverUrlKey] ?: "",
            username = prefs[usernameKey] ?: "",
            password = prefs[passwordKey] ?: "",
            remoteDir = prefs[remoteDirKey] ?: "LiveRecorder",
            keepLocal = prefs[keepLocalKey] ?: true,
        )
    }

    suspend fun set(s: CloudSyncSettings) {
        context.cloudSyncDataStore.edit { prefs ->
            prefs[enabledKey] = s.enabled
            prefs[serverUrlKey] = s.serverUrl.trim()
            prefs[usernameKey] = s.username
            prefs[passwordKey] = s.password
            prefs[remoteDirKey] = s.remoteDir.trim().trimEnd('/').ifBlank { "LiveRecorder" }
            prefs[keepLocalKey] = s.keepLocal
        }
    }
}
