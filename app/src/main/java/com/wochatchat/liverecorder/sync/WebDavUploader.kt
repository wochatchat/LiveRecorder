package com.wochatchat.liverecorder.sync

import com.wochatchat.liverecorder.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Phase 11-11.1：WebDAV 上传（纯 OkHttp：MKCOL 建目录 + PUT 上传）。
 * SMB/FTP 需要额外重依赖，暂不支持（见规划文档 Phase 11 评估）。
 */
open class WebDavUploader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build(),
) {
    data class Creds(
        val serverUrl: String,
        val username: String = "",
        val password: String = "",
    ) {
        val valid: Boolean get() = serverUrl.startsWith("http")
    }

    /** 上传结果：成功 / 目录创建失败 / 上传失败（带 HTTP 码便于排查）。 */
    sealed class Result {
        data object Success : Result()
        data class Failure(val stage: String, val code: Int, val message: String = "") : Result()
    }

    /**
     * 上传单个文件到 [remoteFilePath]（远端绝对路径，含文件名）。
     * 逐级 MKCOL 缺失的父目录（已存在视为成功）。
     */
    suspend fun upload(creds: Creds, remoteFilePath: String, local: File): Result {
        if (!creds.valid) return Result.Failure("config", 0, "serverUrl 非法")
        if (!local.exists() || local.length() == 0L) return Result.Failure("local", 0, "本地文件不存在")

        for (dir in ancestorDirs(remoteFilePath)) {
            val code = execute(mkcolRequest(creds, dir))
            if (code !in SUCCESS_CODES && code != 405 && code != 301) {
                return Result.Failure("mkcol", code, dir)
            }
        }

        val body = local.asRequestBody("application/octet-stream".toMediaType())
        val code = execute(
            Request.Builder()
                .url(remoteFilePath)
                .applyAuth(creds)
                .put(body)
                .build()
        )
        return if (code in SUCCESS_CODES) Result.Success
        else Result.Failure("put", code, local.name)
    }

    private fun mkcolRequest(creds: Creds, dir: String): Request =
        Request.Builder().url(dir).applyAuth(creds).method("MKCOL", null).build()

    private fun Request.Builder.applyAuth(creds: Creds): Request.Builder =
        if (creds.username.isEmpty() && creds.password.isEmpty()) this
        else header("Authorization", Credentials.basic(creds.username, creds.password))

    private suspend fun execute(request: Request): Int = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { it.code }
        } catch (e: Exception) {
            AppLog.e(TAG, "WebDAV 请求失败: ${e.message}")
            -1
        }
    }

    companion object {
        private const val TAG = "WebDavUploader"
        private val SUCCESS_CODES = 200..299

        /**
         * 远端文件路径 → 需要确保存在的祖先目录链（不含根与文件自身，已排序）。
         * 纯函数便于单测。
         */
        fun ancestorDirs(remoteFilePath: String): List<String> {
            val idx = remoteFilePath.trimEnd('/').lastIndexOf('/')
            if (idx <= 0) return emptyList()
            val path = remoteFilePath.substring(0, idx)
            val prefix = if (remoteFilePath.startsWith("/")) "" else null
            val parts = path.trimStart('/').split('/').filter { it.isNotBlank() }
            var acc = prefix ?: ""
            return parts.map { acc += "/$it"; acc }
        }

        /** 远端路径拼接（base 去尾斜杠，rel 去头斜杠）。 */
        fun remotePath(base: String, rel: String): String =
            base.trimEnd('/') + "/" + rel.trimStart('/')
    }
}
