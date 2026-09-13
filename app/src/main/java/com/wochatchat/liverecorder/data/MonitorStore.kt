package com.wochatchat.liverecorder.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "monitor")

/**
 * Monitored live URLs. Stored as one URL per line in a single string key —
 * newline-joined keeps insertion order (a StringSet would not).
 */
class MonitorStore(private val context: Context) {

    private val key = stringPreferencesKey("urls")

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
}
