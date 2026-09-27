package com.wochatchat.liverecorder.ui.navigation

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wochatchat.liverecorder.ui.screens.MonitorScreen
import com.wochatchat.liverecorder.ui.screens.RecordsScreen
import com.wochatchat.liverecorder.ui.screens.SettingsScreen

/** 导航目标（6b-2：3 Tab 骨架）。 */
sealed class Destination(val route: String, val label: String, val icon: ImageVector) {
    data object Monitor : Destination("monitor", "监控", Icons.Default.PlayArrow)
    data object Records : Destination("records", "记录", Icons.Default.Folder)
    data object Settings : Destination("settings", "设置", Icons.Default.Settings)
}

private val bottomNavItems = listOf(
    Destination.Monitor,
    Destination.Records,
    Destination.Settings,
)

/** 6b-2：导航入口——NavHost + 底部 3 Tab。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEach { dest ->
                    NavigationBarItem(
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
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
            composable(Destination.Monitor.route) { MonitorScreen() }
            composable(Destination.Records.route) { RecordsScreen() }
            composable(Destination.Settings.route) { SettingsScreen() }
        }
    }
}