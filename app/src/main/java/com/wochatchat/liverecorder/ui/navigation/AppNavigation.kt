package com.wochatchat.liverecorder.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.data.UpdateChecker
import com.wochatchat.liverecorder.data.UpdateChecker.UpdateInfo
import com.wochatchat.liverecorder.ui.screens.CookieManagementScreen
import com.wochatchat.liverecorder.ui.screens.InAppPlayerScreen
import com.wochatchat.liverecorder.ui.screens.MonitorScreen
import com.wochatchat.liverecorder.ui.screens.OnboardingScreen
import com.wochatchat.liverecorder.ui.screens.RecordsScreen
import com.wochatchat.liverecorder.ui.screens.RecordDetailScreen
import com.wochatchat.liverecorder.ui.screens.SettingsScreen
import com.wochatchat.liverecorder.ui.screens.UpdateDialog
import kotlinx.coroutines.launch
import java.net.URLEncoder

/** 导航目标（6b-2：3 Tab 骨架）。 */
sealed class Destination(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    data object Monitor : Destination("monitor", R.string.tab_monitor, Icons.Default.PlayArrow)
    data object Records : Destination("records", R.string.tab_records, Icons.Default.Folder)
    data object Settings : Destination("settings", R.string.tab_settings, Icons.Default.Settings)
}

/** 设置子页路由（6d R16）。 */
const val ROUTE_COOKIES = "settings/cookies"

/** Phase 3：录制详情页路由（path = URLEncoder.encode(savePath, "UTF-8")）。 */
const val ROUTE_RECORD_DETAIL = "record_detail/{path}"

/** Phase 3：内置播放器路由（path = URLEncoder.encode(savePath, "UTF-8")）。 */
const val ROUTE_PLAYER = "player/{path}"

/** 编码 savePath 用于路由参数（/ 等字符需转义）。 */
fun encodeNavPath(path: String): String = java.net.URLEncoder.encode(path, "UTF-8")

/** 从路由参数解码 savePath。 */
fun decodeNavPath(encoded: String?): String =
    runCatching { java.net.URLDecoder.decode(encoded ?: "", "UTF-8") }.getOrDefault("")

private val bottomNavItems = listOf(
    Destination.Monitor,
    Destination.Records,
    Destination.Settings,
)

/** 6b-2：导航入口——首启引导门控 + NavHost + 底部 3 Tab（6f R21）。 */
@Composable
fun AppNavigation() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appSettings = remember {
        (context.applicationContext as com.wochatchat.liverecorder.RecorderApp).appSettings
    }
    val onboardingDone by appSettings.onboardingCompleted.collectAsState(initial = null)
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Phase 3：更新检查（引导完成后静默执行，有结果则弹 UpdateDialog）
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    val ignoredVersion by appSettings.ignoredVersion.collectAsState(initial = null)
    LaunchedEffect(onboardingDone, ignoredVersion) {
        if (onboardingDone == true) {
            updateInfo = UpdateChecker.check(context, ignoredVersion)
        }
    }
    updateInfo?.let { info ->
        UpdateDialog(
            info = info,
            onIgnore = {
                scope.launch { appSettings.setIgnoredVersion(info.latestVersion) }
                updateInfo = null
            },
            onDismiss = { updateInfo = null },
        )
    }

    when (onboardingDone) {
        // DataStore 首帧未就绪：空白一帧，避免引导页闪现
        null -> Box(Modifier.fillMaxSize())
        false -> OnboardingScreen(
            onComplete = { scope.launch { appSettings.completeOnboarding() } }
        )
        else -> MainScaffold()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val isTopLevel = bottomNavItems.any { it.route == currentDestination?.route }

    Scaffold(
        bottomBar = {
            if (isTopLevel) NavigationBar {
                bottomNavItems.forEach { dest ->
                    NavigationBarItem(
                        icon = { Icon(dest.icon, contentDescription = stringResource(dest.labelRes)) },
                        label = { Text(stringResource(dest.labelRes)) },
                        selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true,
                        onClick = {
                            navController.navigate(dest.route) {
                                // Pop up to the start destination of the graph to
                                // avoid building up a large stack of destinations
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                // Avoid multiple copies of the same destination
                                launchSingleTop = true
                                // Restore state when reselecting a previously selected item
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Monitor.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Destination.Monitor.route) {
                MonitorScreen(
                    onOpenSettings = {
                        navController.navigate(Destination.Settings.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(Destination.Records.route) {
                val onNavigateToDetail: (String) -> Unit = { encodedPath ->
                    navController.navigate("record_detail/$encodedPath")
                }
                RecordsScreen(onNavigateToDetail = onNavigateToDetail)
            }
            composable(Destination.Settings.route) {
                SettingsScreen(
                    onOpenCookies = { navController.navigate(ROUTE_COOKIES) }
                )
            }
            composable(ROUTE_COOKIES) {
                CookieManagementScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_RECORD_DETAIL) { backStackEntry ->
                val encodedPath = backStackEntry.arguments?.getString("path") ?: ""
                val decodedPath = decodeNavPath(encodedPath)
                RecordDetailScreen(
                    savePath = decodedPath,
                    onBack = { navController.popBackStack() },
                    onMerge = { _, _ -> navController.popBackStack() },
                    onPlayAll = { navController.navigate("player/$encodedPath") },
                )
            }
            composable(ROUTE_PLAYER) { backStackEntry ->
                val encodedPath = backStackEntry.arguments?.getString("path") ?: ""
                InAppPlayerScreen(
                    savePath = decodeNavPath(encodedPath),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}