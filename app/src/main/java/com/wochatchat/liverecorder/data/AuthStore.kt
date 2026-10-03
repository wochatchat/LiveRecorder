package com.wochatchat.liverecorder.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.authDataStore by preferencesDataStore(name = "auth")

/**
 * 平台凭据存储（Phase 4b，对齐上游 config.ini 的 [Cookie] 与 [账号密码] 两段）。
 *
 * - Cookie：每平台一条（上游 [Cookie] 段 50+ 平台键），移动端按平台 ASCII 键存储。
 *   cookie 值本身可含 `=`，序列化行按首个 `=` 切分。
 * - 账密：SOOP / FlexTV / PopkonTV / TwitCasting 四个登录平台（上游 login_* 函数入参，
 *   登录成功得到的 cookie 存回 [cookies]，同上游 new_cookies 回写语义）。
 *
 * 明文存储（与上游 config.ini 一致），仅存于应用私有目录。
 */
class AuthStore(private val context: Context) {

    data class PlatformCookie(val key: String, val label: String)

    private val cookiesKey = stringPreferencesKey("cookies")

    /** 平台 → cookie 串。 */
    val cookies: Flow<Map<String, String>> = context.authDataStore.data.map { prefs ->
        parseCookieLines(prefs[cookiesKey].orEmpty())
    }

    /** 四个登录平台的账密（key = 平台键）。 */
    val credentials: Flow<Map<String, Pair<String, String>>> = context.authDataStore.data.map { prefs ->
        LOGIN_PLATFORMS.mapNotNull { p ->
            val user = prefs[stringPreferencesKey("cred_${p.key}_user")].orEmpty()
            val pass = prefs[stringPreferencesKey("cred_${p.key}_pass")].orEmpty()
            if (user.isNotEmpty() || pass.isNotEmpty()) p.key to (user to pass) else null
        }.toMap()
    }

    /** 保存/清除某平台 cookie（空值 = 清除，等价上游配置项置空）。 */
    suspend fun setCookie(platform: String, cookie: String) {
        context.authDataStore.edit { prefs ->
            val current = parseCookieLines(prefs[cookiesKey] ?: "")
            val value = cookie.trim()
            if (value.isEmpty()) prefs[cookiesKey] = formatCookieLines(current - platform)
            else prefs[cookiesKey] = formatCookieLines(current + (platform to value))
        }
    }

    /** 保存某登录平台账密（任一字段为空则整体清除）。 */
    suspend fun setCredential(platform: String, username: String, password: String) {
        val user = username.trim()
        val pass = password.trim()
        context.authDataStore.edit { prefs ->
            prefs[stringPreferencesKey("cred_${platform}_user")] = user
            prefs[stringPreferencesKey("cred_${platform}_pass")] = pass
        }
    }

    // ---- Phase 11-11.2: 多账号 ----

    /** 额外账号（平台 → 账号列表，JSON 持久化；默认账号仍走既有 cookies 键）。 */
    private val accountsKey = stringPreferencesKey("accounts_json")

    /** 平台 → 额外账号列表（不含默认账号，见 [Accounts.withDefault]）。 */
    val accounts: Flow<Map<String, List<Account>>> = context.authDataStore.data.map { prefs ->
        Accounts.fromJson(prefs[accountsKey] ?: "{}")
    }

    /** 新增账号（nickname 空时自动「账号 N」）。 */
    suspend fun addAccount(platform: String, nickname: String, cookie: String) {
        context.authDataStore.edit { prefs ->
            val map = Accounts.fromJson(prefs[accountsKey] ?: "{}").toMutableMap()
            val list = map[platform].orEmpty()
            val name = nickname.trim().ifBlank { "账号${list.size + 1}" }
            map[platform] = list + Account(Accounts.newId(), name, cookie.trim())
            prefs[accountsKey] = Accounts.toJson(map)
        }
    }

    /** 删除账号（id 不存在时静默）。 */
    suspend fun removeAccount(platform: String, accountId: String) {
        context.authDataStore.edit { prefs ->
            val map = Accounts.fromJson(prefs[accountsKey] ?: "{}").toMutableMap()
            map[platform] = map[platform].orEmpty().filterNot { it.id == accountId }
            if (map[platform].isEmpty()) map.remove(platform)
            prefs[accountsKey] = Accounts.toJson(map)
        }
    }

    // ---- Phase 5-5.2: 账号健康度 ----

    /** 账号健康状态（JSON：{"platform":["ok"|"expired",ts], ...}）。 */
    private val accountHealthKey = stringPreferencesKey("account_health")

    /** 平台 → 健康条目（未标记的平台 UI 显示 ⏳）。 */
    val accountHealth: Flow<Map<String, AccountHealthEntry>> = context.authDataStore.data.map { prefs ->
        AccountHealth.fromJson(prefs[accountHealthKey] ?: "{}")
    }

    /**
     * 标记平台账号健康状态（ok/expired）。状态未变化时跳过写入（避免轮询每轮写盘）。
     * 判定来源见 [AccountHealth] 文件头注释。
     */
    suspend fun markAccountHealth(platform: String, status: String, ts: Long = System.currentTimeMillis()) {
        val current = AccountHealth.statusOf(accountHealth.first(), platform)
        if (current == status) return
        context.authDataStore.edit { prefs ->
            val map = AccountHealth.fromJson(prefs[accountHealthKey] ?: "{}")
            prefs[accountHealthKey] = AccountHealth.toJson(AccountHealth.mark(map, platform, status, ts))
        }
    }

    companion object {
        /** 上游 config.ini [Cookie] 段平台键（ASCII 化），label 供 UI 展示。 */
        val COOKIE_PLATFORMS = listOf(
            PlatformCookie("douyin", "抖音"),
            PlatformCookie("kuaishou", "快手"),
            PlatformCookie("tiktok", "TikTok"),
            PlatformCookie("huya", "虎牙"),
            PlatformCookie("douyu", "斗鱼"),
            PlatformCookie("yy", "YY"),
            PlatformCookie("bilibili", "B站"),
            PlatformCookie("xhs", "小红书"),
            PlatformCookie("bigo", "Bigo"),
            PlatformCookie("blued", "Blued"),
            PlatformCookie("sooplive", "SOOP"),
            PlatformCookie("netease", "网易CC"),
            PlatformCookie("qiandurebo", "千度热播"),
            PlatformCookie("pandatv", "PandaTV"),
            PlatformCookie("maoer", "猫耳FM"),
            PlatformCookie("winktv", "WinkTV"),
            PlatformCookie("flextv", "FlexTV"),
            PlatformCookie("look", "网易Look"),
            PlatformCookie("twitcasting", "TwitCasting"),
            PlatformCookie("baidu", "百度"),
            PlatformCookie("weibo", "微博"),
            PlatformCookie("kugou", "酷狗"),
            PlatformCookie("twitch", "Twitch"),
            PlatformCookie("liveme", "LiveMe"),
            PlatformCookie("huajiao", "花椒"),
            PlatformCookie("liuxing", "流星"),
            PlatformCookie("showroom", "ShowRoom"),
            PlatformCookie("acfun", "AcFun"),
            PlatformCookie("changliao", "畅聊"),
            PlatformCookie("yinbo", "音播"),
            PlatformCookie("yingke", "映客"),
            PlatformCookie("zhihu", "知乎"),
            PlatformCookie("chzzk", "CHZZK"),
            PlatformCookie("haixiu", "嗨秀"),
            PlatformCookie("vvxqiu", "VV星球"),
            PlatformCookie("seventeen", "17Live"),
            PlatformCookie("langlive", "浪Live"),
            PlatformCookie("pplive", "漂漂"),
            PlatformCookie("sixroom", "六间房"),
            PlatformCookie("lehaitv", "乐海TV"),
            PlatformCookie("huamao", "花猫"),
            PlatformCookie("shopee", "Shopee"),
            PlatformCookie("youtube", "YouTube"),
            PlatformCookie("taobao", "淘宝"),
            PlatformCookie("jd", "京东"),
            PlatformCookie("faceit", "FACEIT"),
            PlatformCookie("migu", "咪咕"),
            PlatformCookie("lianjie", "连接TV"),
            PlatformCookie("laixiu", "来秀"),
            PlatformCookie("picarto", "Picarto"),
        )

        /** 需账密登录的平台（上游 [账号密码] 段 4 项）。 */
        val LOGIN_PLATFORMS = listOf(
            PlatformCookie("sooplive", "SOOP"),
            PlatformCookie("flextv", "FlexTV"),
            PlatformCookie("popkontv", "PopkonTV"),
            PlatformCookie("twitcasting", "TwitCasting"),
        )

        val ALL_PLATFORMS: List<PlatformCookie> = COOKIE_PLATFORMS

        fun labelOf(key: String): String =
            (COOKIE_PLATFORMS + LOGIN_PLATFORMS).firstOrNull { it.key == key }?.label ?: key

        /** "k=v" 行序列 → map（按首个 `=` 切分；cookie 值本身可含 `=`）。 */
        fun parseCookieLines(raw: String): Map<String, String> =
            raw.split('\n')
                .mapNotNull { line ->
                    val i = line.indexOf('=')
                    if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
                }
                .toMap()

        /** map → "k=v" 行序列（UI 编辑 / 持久化用，键序保持插入序）。 */
        fun formatCookieLines(map: Map<String, String>): String =
            map.entries.joinToString("\n") { "${it.key}=${it.value}" }
    }
}
