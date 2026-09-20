package com.wochatchat.liverecorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wochatchat.liverecorder.recorder.RecordController
import com.wochatchat.liverecorder.ui.MonitorViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                MonitorScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(viewModel: MonitorViewModel = viewModel()) {
    val urls by viewModel.urls.collectAsState()
    val recordStates by viewModel.recordStates.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("直播监控") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "添加直播")
            }
        }
    ) { padding ->
        if (urls.isEmpty()) {
            EmptyState(padding)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(urls, key = { it }) { url ->
                    val state = recordStates[url]
                    MonitorItem(
                        url = url,
                        recordState = state,
                        onRemove = { viewModel.remove(url) },
                        onStart = { viewModel.startRecord(url) },
                        onStop = { viewModel.stopRecord(url) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddUrlDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { url ->
                viewModel.add(url)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("还没有监控的直播间", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "点击右下角 + 添加直播间链接\n支持抖音 / 快手 / 虎牙 / 斗鱼 / B站等",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MonitorItem(
    url: String,
    recordState: RecordController.RecordState?,
    onRemove: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val recording = recordState is RecordController.RecordState.Resolving ||
        recordState is RecordController.RecordState.Recording
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(url, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            Text(
                describeState(recordState),
                style = MaterialTheme.typography.labelSmall,
                color = when (recordState) {
                    is RecordController.RecordState.Recording -> MaterialTheme.colorScheme.error
                    is RecordController.RecordState.Failed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        IconButton(onClick = if (recordState is RecordController.RecordState.Recording) onStop else onStart) {
            Icon(
                if (recordState is RecordController.RecordState.Recording) Icons.Default.Stop
                else Icons.Default.PlayArrow,
                contentDescription = if (recordState is RecordController.RecordState.Recording) "停止" else "录制",
                tint = if (recordState is RecordController.RecordState.Recording)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = "删除", tint = MaterialTheme.colorScheme.outline)
        }
    }
}

private fun describeState(state: RecordController.RecordState?): String = when (state) {
    null -> "未监控"
    is RecordController.RecordState.Resolving -> "解析直播源…"
    is RecordController.RecordState.Recording ->
        "录制中 · ${state.bytes / 1024 / 1024} MB · ${state.savePath.substringAfterLast('/')}"
    is RecordController.RecordState.Finished ->
        if (state.completed) "完成 · ${state.bytes / 1024 / 1024} MB · ${state.savePath.substringAfterLast('/')}"
        else "已停止 · ${state.bytes / 1024 / 1024} MB"
    is RecordController.RecordState.Failed -> "失败: ${state.message}"
}

@Composable
private fun AddUrlDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加直播间") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("粘贴直播间链接") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        },
        confirmButton = {
            IconButton(onClick = { if (text.isNotBlank()) onConfirm(text) }) {
                Icon(Icons.Default.Add, contentDescription = "添加")
            }
        },
        dismissButton = {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "取消")
            }
        }
    )
}
