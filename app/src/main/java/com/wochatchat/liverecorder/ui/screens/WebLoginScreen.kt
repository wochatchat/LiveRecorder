package com.wochatchat.liverecorder.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.data.AuthStore
import com.wochatchat.liverecorder.data.webLoginUrlFor

/**
 * V3-5 R1：全屏 WebView 登录页。登录完成后点「抓取并返回」，
 * 抓取当前域名 Cookie 经 [onDone] 交回调用方预填确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebLoginScreen(
    platformKey: String,
    onDone: (cookie: String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val loginUrl = webLoginUrlFor(platformKey)
    val platformLabel = AuthStore.labelOf(platformKey)
    var progress by remember { mutableIntStateOf(100) }
    var grabbed by remember { mutableStateOf(false) }
    // WebView 实例跨重组保留（key=platformKey），离开页面销毁
    val view = remember(platformKey) {
        loginUrl?.let { createLoginWebView(context, it) { p -> progress = p } }
    }

    DisposableEffect(view) {
        onDispose { view?.destroy() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.web_login_title, platformLabel)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (grabbed || view == null) return@TextButton
                            val url = view.url ?: loginUrl
                            val cookie = url?.let {
                                CookieManager.getInstance().getCookie(it)
                            }
                            if (!cookie.isNullOrBlank()) {
                                grabbed = true
                                onDone(cookie)
                            }
                        },
                        enabled = view != null
                    ) { Text(stringResource(R.string.web_login_grab)) }
                }
            )
        }
    ) { padding ->
        if (view == null) {
            // 未收录平台（理论上不可达：入口已过滤）
            Text(
                stringResource(R.string.web_login_unsupported),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (progress < 100) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                stringResource(R.string.web_login_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            AndroidView(
                factory = { view },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** 构建并配置登录 WebView（启用 JS/DOM/Cookie，加载登录页）。 */
@SuppressLint("SetJavaScriptEnabled")
private fun createLoginWebView(
    context: Context,
    url: String,
    onProgress: (Int) -> Unit,
): WebView {
    val webView = WebView(context)
    webView.settings.javaScriptEnabled = true
    webView.settings.domStorageEnabled = true
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }
    webView.webViewClient = WebViewClient()
    webView.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onProgress(newProgress)
        }
    }
    webView.loadUrl(url)
    return webView
}
