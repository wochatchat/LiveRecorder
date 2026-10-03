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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.ui.components.PlatformBadge
import com.wochatchat.liverecorder.ui.components.platformKeyForUrl
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloat

/** 引导总页数（Phase 7-7.1：欢迎 / 权限 / 添加监控项 / Tutorial 动画 / 开始使用）。 */
private const val PAGE_COUNT = 5

/**
 * 6f R21：首次使用引导（4 页：欢迎与平台 → 权限设置 → 添加第一个监控项 → 开始使用）。
 * 完成后由 AppNavigation 写入 DataStore `onboarding_completed`，之后不再出现。
 * 两项授权均可跳过——通知权限缺失服务仍可运行（2a），电池白名单可在系统设置补开。
 *
 * Phase 7-7.1：新增第 3 页「添加第一个直播间」——粘贴输入 + 快捷链接直接添加
 * （经 [onAddMonitor] 回调写入 MonitorStore），不等用户进 App 再手动添加。
 */

/** 示例链接（仅演示链接格式，点击复制；用户粘贴后替换为真实房间号）。 */
internal val ONBOARDING_EXAMPLE_LINKS = listOf(
    "https://live.douyin.com/123456789" to "抖音",
    "https://live.kuaishou.com/u/example" to "快手",
    "https://live.bilibili.com/12345" to "B站",
)

/** Phase 7-7.1：快捷添加平台（一键加入监控，示例房间号需用户换成真实房间）。 */
internal val ONBOARDING_QUICK_LINKS = listOf(
    "https://live.douyin.com/123456789" to "抖音",
    "https://live.kuaishou.com/u/example" to "快手",
    "https://live.bilibili.com/12345" to "B站",
    "https://www.huya.com/123456" to "虎牙",
    "https://www.douyu.com/123456" to "斗鱼",
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
fun OnboardingScreen(onComplete: () -> Unit, onAddMonitor: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })

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
                2 -> AddMonitorPage(onAddMonitor)
                3 -> TutorialPage()
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
        repeat(PAGE_COUNT) { i ->
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
        if (pagerState.currentPage < PAGE_COUNT - 1) {
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

/**
 * Phase 7-7.1 第 3 页：添加第一个监控项。
 * 粘贴输入 + 快捷链接（点击经 [onAddMonitor] 直接入监控列表），实时平台徽标预览。
 */
@Composable
private fun AddMonitorPage(onAddMonitor: ((String) -> Unit)?) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var addedMsg by remember { mutableStateOf<String?>(null) }

    fun addUrls(raw: String) {
        // 复用监控页的批量分隔约定：多行/逗号分隔
        val urls = raw.split('\n', ',', '，', ';', '；')
            .map { it.trim() }
            .filter { it.startsWith("http") }
        if (urls.isEmpty()) return
        val callback = onAddMonitor ?: return
        urls.forEach { callback(it) }
        addedMsg = context.getString(R.string.onboarding_add_success, urls.size)
        input = ""
    }

    OnboardingScaffold(
        icon = {
            Icon(
                Icons.Default.Add, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp),
            )
        },
        title = stringResource(R.string.onboarding_add_title),
        body = stringResource(R.string.onboarding_add_body),
    ) {
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.onboarding_add_placeholder)) },
            supportingText = { Text(stringResource(R.string.onboarding_add_hint)) },
            trailingIcon = {
                TextButton(onClick = { addUrls(input) }, enabled = input.isNotBlank()) {
                    Text(stringResource(R.string.onboarding_add_btn))
                }
            },
            singleLine = false,
            maxLines = 3,
        )
        addedMsg?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_add_quick),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ONBOARDING_QUICK_LINKS.forEach { (link, platform) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { addUrls(link) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlatformBadge(platformKeyForUrl(link))
                Spacer(Modifier.width(12.dp))
                Text(link, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_add_platform_tip),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * Phase 7-7.3 第 4 页：Tutorial 动画——循环演示「添加 → 录制 → 查看回放」三步流程。
 * 纯 Compose 无限过渡（零依赖）：三个图标按序点亮，当前步放大 + 主色。
 */
@Composable
private fun TutorialPage() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "tutorial")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(3600, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "tutorial_phase",
    )

    // 步骤 i 的点亮程度：phase 越接近 (i + 0.5) 越亮（1.0），两侧线性衰减到 0
    fun highlight(i: Int): Float {
        val d = kotlin.math.abs(phase - (i + 0.5f))
        return (1f - d / 0.5f).coerceIn(0f, 1f)
    }

    val steps = listOf(
        Triple(Icons.Default.Add, stringResource(R.string.onboarding_tutorial_step_add), 0),
        Triple(Icons.Default.PlayArrow, stringResource(R.string.onboarding_tutorial_step_record), 1),
        Triple(Icons.Default.Folder, stringResource(R.string.onboarding_tutorial_step_view), 2),
    )

    OnboardingScaffold(
        icon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                steps.forEachIndexed { idx, (icon, label, i) ->
                    if (idx > 0) {
                        Text(
                            "→",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val h = highlight(i)
                        Icon(
                            icon, contentDescription = label,
                            tint = if (h > 0.5f) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier
                                .size(56.dp)
                                .graphicsLayer {
                                    scaleX = 1f + 0.25f * h
                                    scaleY = 1f + 0.25f * h
                                    alpha = 0.45f + 0.55f * h
                                },
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (h > 0.5f) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        title = stringResource(R.string.onboarding_tutorial_title),
        body = stringResource(R.string.onboarding_tutorial_body),
    )
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
