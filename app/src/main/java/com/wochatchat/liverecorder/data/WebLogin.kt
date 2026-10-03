package com.wochatchat.liverecorder.data

/**
 * V3-5 R1：网页登录取 cookie。
 * 平台键（AuthStore cookie 键）→ 登录页 URL；未收录平台回落手动粘贴。
 */
val WEB_LOGIN_URLS: Map<String, String> = mapOf(
    "douyin" to "https://www.douyin.com/",
    "kuaishou" to "https://www.kuaishou.com/",
    "bilibili" to "https://passport.bilibili.com/login",
    "huya" to "https://www.huya.com/",
    "douyu" to "https://www.douyu.com/",
    "xhs" to "https://www.xiaohongshu.com/",
)

/** 平台是否支持网页登录取 cookie；支持则返回登录页 URL。 */
fun webLoginUrlFor(platformKey: String): String? = WEB_LOGIN_URLS[platformKey]

/**
 * Cookie 头（"k1=v1; k2=v2"）→ 键值对（纯函数便于单测）。
 * 无 = 的残片忽略；键 trim；同名键后者覆盖。
 */
fun parseCookiePairs(cookieHeader: String): Map<String, String> =
    cookieHeader.split(';')
        .mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null
            else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }
        .toMap()
