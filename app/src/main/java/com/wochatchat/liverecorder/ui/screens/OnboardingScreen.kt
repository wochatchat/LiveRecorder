package com.wochatchat.liverecorder.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wochatchat.liverecorder.R
import kotlinx.coroutines.launch

/**
 * 6f R21：首次使用引导（3 页：欢迎与平台 → 权限设置 → 开始使用）。
 * 完成后由 AppNavigation 写入 DataStore `onboarding_completed`，之后不再出现。
 * 两项授权均可跳过——通知权限缺失服务仍可运行（2a），电池白名单可在系统设置补开。
 */

/** 示例链接（仅演示链接格式，点击复制；用户粘贴后替换为真实房间号）。 */
internal val ONBOARDING_EXAMPLE_LINKS = listOf(
    "https://live.douyin.com/123456789" to "抖音",
    "https://live.kuaishou.com/u/example" to "快手",
    "https://live.bilibili.com/12345" to "B站",
)

/** 通知权限是否已授（Android 13 以下视为已授）。 */
internal fun notifPermissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

/** 是否已在电池优化白名单。 */
internal fun batteryWhitelisted(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(context.packageName)

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { 3 })

    // 权限状态：进页 + 每次回到前台时重算（从系统设置返回后刷新）
    var notifOk by remember { mutableStateOf(notifPermissionGranted(context)) }
    var batteryOk by remember { mutableStateOf(batteryWhitelisted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifOk = notifPermissionGranted(context)
                batteryOk = batteryWhitelisted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notifOk = notifPermissionGranted(context) }

    fun requestBattery() {
        // 直接拉系统白名单授权弹窗；被厂商 ROM 拦截时回落设置列表页
        try {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                )
            )
        } catch (e: Exception) {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> WelcomePage()
                1 -> PermissionPage(
                    notifOk = notifOk,
                    batteryOk = batteryOk,
                    onRequestNotif = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onRequestBattery = ::requestBattery,
                )
                else -> StartPage()
            }
        }
        OnboardingFooter(pagerState = pagerState, onComplete = onComplete)
    }
}

/** 页指示点 + 底部按钮（上一步 / 跳过+下一步 / 开始使用）。 */
@Composable
private fun OnboardingFooter(pagerState: androidx.compose.foundation.pager.PagerState, onComplete: () -> Unit) {
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(8.dp)
                    .background(
                        if (i == pagerState.currentPage) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    )
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pagerState.currentPage > 0) {
            TextButton(onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } }) {
                Text(stringResource(R.string.onboarding_action_back))
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        if (pagerState.currentPage < 2) {
            Row {
                TextButton(onClick = { onComplete() }) {
                    Text(stringResource(R.string.onboarding_action_skip))
                }
                Button(onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } }) {
                    Text(stringResource(R.string.onboarding_action_next))
                }
            }
        } else {
            Button(onClick = { onComplete() }) {
                Text(stringResource(R.string.onboarding_action_start))
            }
        }
    }
}

/** 第 1 页：欢迎与支持平台。 */
@Composable
private fun WelcomePage() {
    OnboardingScaffold(
        icon = {
            Icon(
                Icons.Default.PlayArrow, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp),
            )
        },
        title = stringResource(R.string.onboarding_welcome_title),
        body = stringResource(R.string.onboarding_welcome_body),
    )
}

/** 第 2 页：权限设置（通知 + 电池优化白名单）。 */
@Composable
private fun PermissionPage(
    notifOk: Boolean,
    batteryOk: Boolean,
    onRequestNotif: () -> Unit,
    onRequestBattery: () -> Unit,
) {
    OnboardingScaffold(
        icon = {
            Icon(
                Icons.Default.Bolt, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp),
            )
        },
        title = stringResource(R.string.onboarding_perm_title),
        body = stringResource(R.string.onboarding_perm_body),
    ) {
        Spacer(Modifier.height(16.dp))
        PermissionCard(
            icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
            title = stringResource(R.string.onboarding_perm_notif),
            desc = stringResource(R.string.onboarding_perm_notif_desc),
            done = notifOk,
            action = stringResource(
                if (notifOk) R.string.onboarding_perm_granted else R.string.onboarding_perm_grant
            ),
            onAction = onRequestNotif,
        )
        Spacer(Modifier.height(12.dp))
        PermissionCard(
            icon = { Icon(Icons.Default.Bolt, contentDescription = null) },
            title = stringResource(R.string.onboarding_perm_battery),
            desc = stringResource(R.string.onboarding_perm_battery_desc),
            done = batteryOk,
            action = stringResource(
                if (batteryOk) R.string.onboarding_perm_done else R.string.onboarding_perm_grant
            ),
            onAction = onRequestBattery,
        )
    }
}

/** 第 3 页：使用流程说明 + 示例链接（点击复制）。 */
@Composable
private fun StartPage() {
    val context = LocalContext.current
    var copiedIdx by remember { mutableStateOf(-1) }
    OnboardingScaffold(
        icon = {
            Icon(
                Icons.Default.PlayArrow, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp),
            )
        },
        title = stringResource(R.string.onboarding_start_title),
        body = stringResource(R.string.onboarding_start_body),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_example_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ONBOARDING_EXAMPLE_LINKS.forEachIndexed { idx, (link, platform) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", link))
                        copiedIdx = idx
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.ContentCopy, contentDescription = null,
                    modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.width(8.dp))
                Text(platform, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (copiedIdx == idx) stringResource(R.string.onboarding_copied) else link,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (copiedIdx == idx) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 引导页通用骨架：图标 + 标题 + 说明文字 + 可选附加内容（垂直居中）。 */
@Composable
private fun OnboardingScaffold(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    extra: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        extra()
    }
}

/** 单项权限卡片：图标 + 标题/说明 + 右侧操作按钮（完成后变已授权态）。 */
@Composable
private fun PermissionCard(
    icon: @Composable () -> Unit,
    title: String,
    desc: String,
    done: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    desc, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onAction, enabled = !done) {
                Text(action)
            }
        }
    }
}
