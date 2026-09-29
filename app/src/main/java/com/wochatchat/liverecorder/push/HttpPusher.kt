package com.wochatchat.liverecorder.push

import com.wochatchat.liverecorder.data.AppLog
import com.wochatchat.liverecorder.net.LiveHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** 推送配置（MonitorStore 持久化 / HttpPusher 消费）。 */
data class PushConfig(
    val enabled: Boolean = false,
    val type: String = "ntfy",
    val apis: List<String> = emptyList(),
    /** 7b R38：自定义推送标题（上游 config.ini「自定义推送标题」，空 → 默认）。 */
    val title: String = "",
    /** 自定义开播文案，占位符 [直播间名称]/[时间]（上游 main.py:1101-1104，空 → 默认文案）。 */
    val liveMessage: String = "",
    /** 自定义关播文案（上游 main.py:1082-1084）。 */
    val offlineMessage: String = "",
    /** bark 中断级别（active/timeSensitive/critical/passive，空 → active）。 */
    val barkLevel: String = "",
    /** bark 铃声（上游 bark_msg_ring，空 → 不定制）。 */
    val barkSound: String = "",
    /** ntfy tags（逗号分隔 emoji shortcode，空 → partying_face）。 */
    val ntfyTags: String = "",
    /** ntfy 优先级 1-5（0 → 默认 3）。 */
    val ntfyPriority: Int = 0,
) {
    val isValid: Boolean get() = enabled && apis.isNotEmpty()

    companion object {
        const val TYPE_NTFY = "ntfy"
        const val TYPE_BARK = "bark"
    }
}

/** 推送事件类型。 */
enum class Event { LIVE, OFFLINE }

/**
 * HTTP 推送（Phase 2-2f），对齐上游 msg_push.py 的 bark()/ntfy()：
 * 只保留 ntfy / bark 两种（微信/钉钉/TG/邮箱/息知/pushplus 砍掉）。
 *
 * - 支持多个推送地址，中英文逗号分隔（上游 api.replace('，', ',').split(',')）
 * - ntfy 地址形如 https://ntfy.sh/topic，取最后一段为 topic（上游 rsplit('/', 1)），
 *   POST 到 server；响应 JSON 含 "error" 视为失败
 * - bark 直接 POST 到完整地址；响应 JSON code==200 视为成功
 * - 推送失败不影响主流程（仅记录日志，上游同语义）
 */
class HttpPusher(private val client: LiveHttpClient = LiveHttpClient(timeoutSec = 10)) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 开播推送（fire-and-forget，不阻塞调用方）。 */
    fun pushLiveAsync(config: PushConfig, anchorName: String, timeStr: String, liveUrl: String) {
        pushAsync(config, Event.LIVE, anchorName, timeStr, liveUrl)
    }

    /** 关播推送（fire-and-forget）。 */
    fun pushOfflineAsync(config: PushConfig, anchorName: String, timeStr: String, liveUrl: String) {
        pushAsync(config, Event.OFFLINE, anchorName, timeStr, liveUrl)
    }

    private fun pushAsync(config: PushConfig, event: Event, anchorName: String, timeStr: String, liveUrl: String) {
        if (!config.isValid) return
        scope.launch {
            val failed = push(config, event, anchorName, timeStr, liveUrl)
            if (failed.isNotEmpty()) AppLog.w(TAG, "推送失败: $failed")
        }
    }

    /**
     * 单次推送（挂起）。返回失败地址列表（空 = 全部成功）。
     * 文案对齐上游 main.py push_message：开播「[名称] 正在直播中，时间：[时间]」、
     * 关播「[名称] 直播已结束！时间：[时间]」。
     */
    suspend fun push(
        config: PushConfig,
        event: Event,
        anchorName: String,
        timeStr: String,
        liveUrl: String,
    ): List<String> {
        val title = buildTitle(config)
        val content = buildContent(config, event, anchorName, timeStr)

        val failed = mutableListOf<String>()
        for (api in config.apis) {
            // ntfy 的 topic 取自地址末段（每地址不同），body 需按地址构造（上游同语义）
            val body = when (config.type.lowercase()) {
                TYPE_BARK -> barkBody(api, title, content, level = config.barkLevel, sound = config.barkSound)
                else -> ntfyBody(api, title, content, actionUrl = liveUrl, tags = parseTags(config.ntfyTags), priority = coercePriority(config.ntfyPriority))
            }
            val ok = if (config.type.equals(TYPE_BARK, ignoreCase = true)) pushBark(api, body)
            else pushNtfy(api, body)
            if (!ok) failed.add(api)
        }
        return failed
    }

    /** 7b R38：构建推送标题（config.title 非空用用户模板，否则默认）。 */
    internal fun buildTitle(config: PushConfig): String =
        config.title.trim().ifEmpty { DEFAULT_TITLE }

    /**
     * 7b R38：构建推送内容——用户自定义模板或默认文案，占位符 [直播间名称]/[时间]
     * （上游 main.py:1099-1104 替换逻辑）。
     */
    internal fun buildContent(config: PushConfig, event: Event, anchorName: String, timeStr: String): String {
        val raw = when (event) {
            Event.LIVE -> config.liveMessage.ifBlank { DEFAULT_LIVE_CONTENT }
            Event.OFFLINE -> config.offlineMessage.ifBlank { DEFAULT_OFFLINE_CONTENT }
        }
        return raw
            .replace("[直播间名称]", anchorName)
            .replace("[时间]", timeStr)
            .replace("\\n", "\n")   // 上游 push_content.replace(r'\n', '\n') 同语义
    }

    /** 组装 ntfy 请求体（internal 便于单测；[api] 已去掉 topic 前缀前的部分）。 */
    internal fun ntfyBody(
        api: String,
        title: String,
        message: String,
        actionUrl: String,
        tags: List<String> = listOf(NTFY_TAG),
        priority: Int = NTFY_PRIORITY,
    ): String {
        val topic = api.substringAfterLast('/')
        val json = JSONObject().apply {
            put("topic", topic)
            put("title", title)
            put("message", message)
            put("tags", JSONArray().apply { tags.forEach { put(it) } })
            put("priority", priority)
            put("actions", if (actionUrl.isBlank()) JSONArray()
                else JSONArray().put(JSONObject().put("action", "view").put("label", "view live").put("url", actionUrl)))
            put("markdown", false)
        }
        return json.toString()
    }

    /** ntfy tags 串（中英文逗号分隔）→ 列表；空/全空白回落默认 partying_face。 */
    internal fun parseTags(raw: String): List<String> =
        raw.replace('，', ',').split(',').map { it.trim() }.filter { it.isNotBlank() }
            .ifEmpty { listOf(NTFY_TAG) }

    /** ntfy 优先级：0/越界 → 默认 3，其余 1-5。 */
    internal fun coercePriority(priority: Int): Int =
        if (priority in 1..5) priority else NTFY_PRIORITY

    /** 组装 bark 请求体（internal 便于单测；level/sound 默认值保持现有测试兼容）。 */
    internal fun barkBody(
        api: String,
        title: String,
        message: String,
        level: String = BARK_LEVEL,
        sound: String = "",
    ): String = JSONObject().apply {
        put("title", title)
        put("body", message)
        put("level", level.ifBlank { BARK_LEVEL })
        put("badge", BARK_BADGE)
        put("autoCopy", 1)
        put("sound", sound)
        put("icon", "")
        put("group", "")
        put("isArchive", 1)
        put("url", "")
    }.toString()

    /** ntfy 单地址推送。 */
    private suspend fun pushNtfy(api: String, body: String): Boolean = try {
        val server = api.substringBeforeLast('/')
        val resp = client.post(server, body = body.toRequestBody(JSON_MEDIA))
        resp.isSuccess && !resp.text.contains("\"error\"")
    } catch (e: Exception) {
        AppLog.w(TAG, "ntfy推送失败, 推送地址：$api, 错误信息:${e.message}")
        false
    }

    /** bark 单地址推送。 */
    private suspend fun pushBark(api: String, body: String): Boolean = try {
        val resp = client.post(api, body = body.toRequestBody(JSON_MEDIA))
        resp.isSuccess && runCatching { JSONObject(resp.text).optInt("code") == 200 }.getOrDefault(false)
    } catch (e: Exception) {
        AppLog.w(TAG, "Bark推送失败, 推送地址：$api, 错误信息:${e.message}")
        false
    }

    companion object {
        private const val TAG = "HttpPusher"

        const val TYPE_NTFY = "ntfy"
        const val TYPE_BARK = "bark"

        /** 文案对齐上游 push_message_title / push_content 模板。 */
        const val DEFAULT_TITLE = "直播间状态更新通知"
        const val MSG_PREFIX = "直播间状态更新："

        /** 默认开播/关播文案（上游 main.py:1083/1101，占位符在 buildContent 替换）。 */
        const val DEFAULT_LIVE_CONTENT = "直播间状态更新：[直播间名称] 正在直播中，时间：[时间]"
        const val DEFAULT_OFFLINE_CONTENT = "直播间状态更新：[直播间名称] 直播已结束！时间：[时间]"

        const val NTFY_TAG = "partying_face"
        const val NTFY_PRIORITY = 3
        const val BARK_LEVEL = "active"
        const val BARK_BADGE = 1

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
