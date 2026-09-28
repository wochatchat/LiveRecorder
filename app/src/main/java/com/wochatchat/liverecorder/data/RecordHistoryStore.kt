package com.wochatchat.liverecorder.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.historyDataStore by preferencesDataStore(name = "history")

/**
 * 单条录制记录（6e R18）：录制结束（Finished，且确有落盘文件）时写入。
 * [savePath] OkHttp 单文件为具体文件；ffmpeg 分段模式为目录（删除用递归）。
 */
data class RecordHistoryEntry(
    val url: String,
    val platform: String,
    val anchorName: String,
    val title: String,
    val savePath: String,
    val endTimeMs: Long,
    val durationMs: Long,
    val bytes: Long,
    val completed: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("url", url)
        put("platform", platform)
        put("anchorName", anchorName)
        put("title", title)
        put("savePath", savePath)
        put("endTimeMs", endTimeMs)
        put("durationMs", durationMs)
        put("bytes", bytes)
        put("completed", completed)
    }

    companion object {
        fun fromJson(o: JSONObject): RecordHistoryEntry = RecordHistoryEntry(
            url = o.optString("url"),
            platform = o.optString("platform"),
            anchorName = o.optString("anchorName"),
            title = o.optString("title"),
            savePath = o.optString("savePath"),
            endTimeMs = o.optLong("endTimeMs"),
            durationMs = o.optLong("durationMs"),
            bytes = o.optLong("bytes"),
            completed = o.optBoolean("completed"),
        )
    }
}

/**
 * 录制历史存储（6e R18，独立 DataStore "history"）。
 * 序列化为 JSON 数组存单键（字符串字段由 JSON 转义兜底），按结束时间倒序保留最近
 * [MAX_ENTRIES] 条，超出删最旧。
 */
class RecordHistoryStore(private val context: Context) {

    private val key = stringPreferencesKey("entries")

    /** 全部记录，按结束时间倒序（新→旧）。 */
    val entries: Flow<List<RecordHistoryEntry>> = context.historyDataStore.data.map { prefs ->
        deserialize(prefs[key] ?: "")
    }

    /** 写入一条记录并裁剪到上限。 */
    suspend fun add(entry: RecordHistoryEntry) {
        context.historyDataStore.edit { prefs ->
            val list = deserialize(prefs[key] ?: "") + entry
            prefs[key] = serialize(cap(list, MAX_ENTRIES))
        }
    }

    /** 删除单条记录（文件删除由调用方负责）。 */
    suspend fun remove(entry: RecordHistoryEntry) {
        context.historyDataStore.edit { prefs ->
            val list = deserialize(prefs[key] ?: "").filterNot { it == entry }
            prefs[key] = serialize(cap(list, MAX_ENTRIES))
        }
    }

    companion object {
        /** 保留最近条数（6e R18：500 条）。 */
        const val MAX_ENTRIES = 500

        /** 按结束时间倒序排序并截取前 [max] 条。 */
        fun cap(list: List<RecordHistoryEntry>, max: Int): List<RecordHistoryEntry> =
            list.sortedByDescending { it.endTimeMs }.take(max)

        fun serialize(list: List<RecordHistoryEntry>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun deserialize(raw: String): List<RecordHistoryEntry> = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { RecordHistoryEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}
