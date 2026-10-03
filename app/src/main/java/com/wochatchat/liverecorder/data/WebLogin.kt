package com.wochatchat.liverecorder.data

/**
 * V3-5 R1：网页登录取 cookie。
 * 平台键（AuthStore cookie 键）→ 登录页 URL；未收录平台回落手动粘贴。
 */
val WEB_LOGIN_URLS: Map<String, String> = mapOf(
    // R1 六主平台
    "douyin" to "https://www.douyin.com/",
    "kuaishou" to "https://www.kuaishou.com/",
    "bilibili" to "https://passport.bilibili.com/login",
    "huya" to "https://www.huya.com/",
    "douyu" to "https://www.douyu.com/",
    "xhs" to "https://www.xiaohongshu.com/",
    // R2 扩展覆盖（键对齐 AuthStore cookie 键）
    "tiktok" to "https://www.tiktok.com/login",
    "twitch" to "https://www.twitch.tv/login",
    "youtube" to "https://www.youtube.com/",
    "yy" to "https://www.yy.com/",
    "netease" to "https://cc.163.com/",
    "baidu" to "https://live.baidu.com/",
    "weibo" to "https://weibo.com/login.php",
    "zhihu" to "https://www.zhihu.com/signin",
    "acfun" to "https://www.acfun.cn/login",
    "maoer" to "https://www.missevan.com/",
    "kugou" to "https://fanxing.kugou.com/",
    "bigo" to "https://www.bigo.tv/",
    "blued" to "https://www.blued.cn/",
    "sooplive" to "https://www.sooplive.co.kr/",
    "pandatv" to "https://www.pandalive.co.kr/",
    "winktv" to "https://www.winktv.co.kr/",
    "flextv" to "https://www.flextv.co.kr/",
    "chzzk" to "https://chzzk.naver.com/",
    "seventeen" to "https://17.live/",
    "showroom" to "https://www.showroom-live.com/",
    "twitcasting" to "https://twitcasting.tv/",
)

/** 平台登录态 Cookie 键特征（命中任意一个即认为已登录）；未收录平台不校验。 */
val LOGIN_STATE_KEYS: Map<String, List<String>> = mapOf(
    "douyin" to listOf("sessionid", "sessionid_ss"),
    "kuaishou" to listOf("passToken", "kuaishou.server.web_st", "kuaishou.server.webday7_st"),
    "bilibili" to listOf("SESSDATA"),
    "huya" to listOf("udb_passdata", "yyuid"),
    "douyu" to listOf("acf_uid", "acf_st"),
    "xhs" to listOf("web_session"),
    "tiktok" to listOf("sessionid"),
    "twitch" to listOf("auth-token", "login"),
    "youtube" to listOf("SID", "SAPISID"),
    "weibo" to listOf("SUB"),
)

/** 平台是否支持网页登录取 cookie；支持则返回登录页 URL。 */
fun webLoginUrlFor(platformKey: String): String? = WEB_LOGIN_URLS[platformKey]

/**
 * 抓到的 Cookie 是否包含该平台的登录态键（纯函数便于单测）。
 * 未收录登录态特征的平台一律视为有效（不误报）。
 */
fun webLoginCookieLooksValid(platformKey: String, cookieHeader: String): Boolean {
    val keys = LOGIN_STATE_KEYS[platformKey] ?: return true
    val present = parseCookiePairs(cookieHeader).keys
    return keys.any { it in present }
}

/**
 * Cookie 头（"k1=v1; k2=v2"）→ 键值对（纯函数便于单测）。
 * 无 = 的残片忽略；键 trim；同名键后者覆盖。
 */
fun parseCookiePairs(cookieHeader: String): Map<String, String> =
    cookieHeader.split(';')
        .mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) return@mapNotNull null
            val key = part.substring(0, i).trim()
            if (key.isEmpty()) null else key to part.substring(i + 1).trim()
        }
        .toMap()
