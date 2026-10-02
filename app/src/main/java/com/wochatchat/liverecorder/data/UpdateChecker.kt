package com.wochatchat.liverecorder.data

import android.content.Context
import android.content.pm.PackageManager
import com.wochatchat.liverecorder.net.LiveHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Phase 3：应用内更新检查器。
 * 从 GitHub Releases 公开 API 获取最新版本，与当前安装版本比较，
 * 决定是否弹出更新提示。
 */
object UpdateChecker {

    private const val GITHUB_API =
        "https://api.github.com/repos/wochatchat/LiveRecorder/releases/latest"

    /** 更新信息（供 UpdateDialog 展示）。 */
    data class UpdateInfo(
        val latestVersion: String,
        val currentVersion: String,
        val apkUrl: String,
        val releaseNotes: String,
    )

    /**
     * 检查是否有可用更新。
     * @param context 用于读取当前安装版本
     * @param ignoredVersion 已忽略的版本（忽略后不再提示）
     * @return UpdateInfo（需要更新）或 null（无需更新 / 跳过）
     */
    suspend fun check(context: Context, ignoredVersion: String?): UpdateInfo? =
        withContext(Dispatchers.IO) {
            val currentVersion = getCurrentVersion(context)
            val latestInfo = fetchLatestRelease() ?: return@withContext null

            // 跳过 prerelease / draft
            if (latestInfo.prerelease || latestInfo.draft) return@withContext null

            // 版本比较：相同 → 无需更新
            val latest = latestInfo.version
            if (latest.equals(currentVersion, ignoreCase = true)) return@withContext null

            // 已在忽略列表
            if (latest.equals(ignoredVersion, ignoreCase = true)) return@withContext null

            UpdateInfo(
                latestVersion = latest,
                currentVersion = currentVersion,
                apkUrl = latestInfo.apkUrl,
                releaseNotes = latestInfo.releaseNotes,
            )
        }

    /** 读取当前安装的版本号。 */
    private fun getCurrentVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
    }

    /** 通过 GitHub API 获取最新 Release 信息。 */
    private suspend fun fetchLatestRelease(): GithubRelease? {
        return try {
            val client = LiveHttpClient(timeoutSec = 15L)
            val result = client.get(GITHUB_API)
            if (!result.isSuccess) return null
            parseRelease(result.text)
        } catch (e: Exception) {
            null
        }
    }

    internal data class GithubRelease(
        val version: String,
        val prerelease: Boolean,
        val draft: Boolean,
        val apkUrl: String,
        val releaseNotes: String,
    )

    /** 解析 GitHub Release JSON。 */
    private fun parseRelease(json: String): GithubRelease {
        val obj = JSONObject(json)
        // tag_name 形如 "v0.2.3"，去掉前缀 v
        val tag = obj.optString("tag_name", "")
        val version = if (tag.startsWith("v", ignoreCase = true)) {
            tag.substring(1)
        } else {
            tag
        }
        val prerelease = obj.optBoolean("prerelease", false)
        val draft = obj.optBoolean("draft", false)
        val body = obj.optString("body", "")

        // 从 assets 找第一个 .apk 文件
        val assets = obj.optJSONArray("assets") ?: org.json.JSONArray()
        var apkUrl = ""
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkUrl = asset.optString("browser_download_url", "")
                break
            }
        }

        // 提取 release notes（取前 500 字符）
        val releaseNotes = body
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .take(20)
            .joinToString("\n")
            .take(500)

        return GithubRelease(
            version = version,
            prerelease = prerelease,
            draft = draft,
            apkUrl = apkUrl,
            releaseNotes = releaseNotes,
        )
    }
}
