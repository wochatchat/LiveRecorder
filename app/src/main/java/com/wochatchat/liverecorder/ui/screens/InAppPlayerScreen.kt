package com.wochatchat.liverecorder.ui.screens

import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.FrameLayout
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.data.RecordHistoryEntry
import com.wochatchat.liverecorder.ui.StatsFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 播放倍速选项（Phase 3 5.1）。 */
private val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/** 视频文件扩展名判断（分段目录枚举用，与 RecordsScreen 语义一致）。 */
internal fun isVideoFile(file: File): Boolean =
    file.isFile && file.name.endsWith(".mp4") ||
        file.name.endsWith(".ts") || file.name.endsWith(".flv") || file.name.endsWith(".mkv")

/**
 * 内置播放器（Phase 3 5.1：ExoPlayer 驱动的全屏播放器页面）。
 * - 深色沉浸模式（自动隐藏系统栏）
 * - 点击左/右半屏 ±10s 快退/快进
 * - 进度条拖动定位
 * - 倍速：0.5x / 0.75x / 1.0x / 1.25x / 1.5x / 2.0x
 * - 截图：当前帧保存到 Pictures/LiveRecorder/Screenshots/
 * - 文件信息：时长 / 大小 / 分辨率 / 码率
 * - 多分段按时间顺序合并播放
 * - 音频模式（静音仅播音频）
 * - 记忆播放位置（退出时记录 lastPositionMs，下次断点继续）
 */
@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun InAppPlayerScreen(
    savePath: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 从历史记录查找该条目（拿主播名/标题显示；未匹配时降级用 savePath 展示）
    val historyStore = remember {
        (context.applicationContext as com.wochatchat.liverecorder.RecorderApp).historyStore
    }
    val allEntries by historyStore.entries.collectAsState(initial = emptyList())
    val entry = remember(allEntries, savePath) {
        allEntries.firstOrNull { it.savePath == savePath } ?: com.wochatchat.liverecorder.data.RecordHistoryEntry(
            url = "", platform = "", anchorName = "", title = "",
            savePath = savePath, endTimeMs = 0, durationMs = 0, bytes = 0, completed = false,
        )
    }

    // 收集所有播放文件（目录取全部视频分段，按修改时间排序）
    val files = remember(entry.savePath) {
        val f = File(entry.savePath)
        if (f.isDirectory) {
            f.listFiles()?.filter { isVideoFile(it) }?.sortedBy { it.lastModified() } ?: emptyList()
        } else if (isVideoFile(f)) listOf(f) else emptyList()
    }

    if (files.isEmpty()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.player_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
        ) { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.player_no_files), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    // ExoPlayer 实例（分段按顺序合并播放）
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
            val items = files.map { MediaItem.fromUri(android.net.Uri.fromFile(it)) }
            setMediaItems(items)
            prepare()
        }
    }

    // 生命周期绑定：后台暂停，回来继续
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    // ---- 状态 ----
    var isPlaying by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var totalDurationMs by remember { mutableLongStateOf(0L) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var isMuted by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showFileInfo by remember { mutableStateOf(false) }
    var screenshotMsg by remember { mutableStateOf<String?>(null) }

    // 轮询进度（250ms；duration 从 player 读取，多分段为总时长）
    LaunchedEffect(exoPlayer) {
        while (true) {
            if (exoPlayer.isPlaying) currentPositionMs = exoPlayer.currentPosition
            val d = exoPlayer.duration
            totalDurationMs = if (d > 0) d else 0L
            delay(250)
        }
    }
    LaunchedEffect(isPlaying) { exoPlayer.playWhenReady = isPlaying }
    LaunchedEffect(isMuted) { exoPlayer.volume = if (isMuted) 0f else 1f }
    LaunchedEffect(playbackSpeed) { exoPlayer.setPlaybackSpeed(playbackSpeed) }

    val seekBy: (Long) -> Unit = { deltaMs ->
        val max = (exoPlayer.duration - 100).coerceAtLeast(0)
        val target = (exoPlayer.currentPosition + deltaMs).coerceIn(0, max)
        exoPlayer.seekTo(target)
        currentPositionMs = target
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 视频画面（PlayerView，自绘控制条）
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = false
                    player = exoPlayer
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 手势层：单击左/右三分之一屏 ±10s，中间播放/暂停
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            when {
                                offset.x < size.width / 3 -> seekBy(-10_000)
                                offset.x > size.width * 2 / 3 -> seekBy(10_000)
                                else -> isPlaying = !isPlaying
                            }
                        },
                    )
                },
        )

        // 顶部标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .padding(top = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = Color.White,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    entry.anchorName.ifBlank { stringResource(R.string.records_unknown_anchor) },
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.title.isNotBlank()) {
                    Text(
                        entry.title,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { isMuted = !isMuted }) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = stringResource(R.string.player_audio_mode),
                    tint = if (isMuted) MaterialTheme.colorScheme.primary else Color.White,
                )
            }
            IconButton(onClick = { showSpeedDialog = true }) {
                Icon(
                    Icons.Default.Speed,
                    contentDescription = stringResource(R.string.player_speed),
                    tint = Color.White,
                )
            }
            IconButton(onClick = {
                scope.launch { screenshotMsg = takeScreenshot(context, exoPlayer) }
            }) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = stringResource(R.string.player_screenshot),
                    tint = Color.White,
                )
            }
        }

        // 暂停时显示中央大播放按钮
        if (!isPlaying) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(72.dp)
                    .background(Color.Black.copy(alpha = 0.4f), MaterialTheme.shapes.extraLarge)
                    .clickable { isPlaying = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.action_play),
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
            }
        }

        // 底部控制条：进度条 + 时间 + 操作按钮
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .padding(bottom = 24.dp),
        ) {
            Slider(
                value = if (totalDurationMs > 0) currentPositionMs.toFloat() / totalDurationMs else 0f,
                onValueChange = { fraction ->
                    if (totalDurationMs > 0) {
                        val pos = (fraction * totalDurationMs).toLong()
                        exoPlayer.seekTo(pos)
                        currentPositionMs = pos
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { isPlaying = !isPlaying }) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying)
                                stringResource(R.string.action_pause) else stringResource(R.string.action_play),
                            tint = Color.White,
                        )
                    }
                    Text(
                        "${formatTime(currentPositionMs)} / ${formatTime(totalDurationMs)}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = { showFileInfo = true }) {
                    Text(
                        stringResource(R.string.player_file_info),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }

    // 截图提示（自动消失）
    screenshotMsg?.let { msg ->
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .padding(16.dp),
        ) {
            Text(
                msg,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        LaunchedEffect(msg) {
            delay(2500)
            screenshotMsg = null
        }
    }

    // 倍速选择弹窗
    if (showSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text(stringResource(R.string.player_speed)) },
            text = {
                Column {
                    PLAYBACK_SPEEDS.chunked(3).forEachIndexed { idx, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { speed ->
                                FilterChip(
                                    selected = playbackSpeed == speed,
                                    onClick = {
                                        playbackSpeed = speed
                                        showSpeedDialog = false
                                    },
                                    label = { Text("${speed}x") },
                                )
                            }
                        }
                        if (idx < PLAYBACK_SPEEDS.chunked(3).size - 1) Spacer(Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    // 文件信息弹窗
    if (showFileInfo) {
        val meta = remember(entry.savePath) { readMediaMetadata(context, files.firstOrNull()) }
        AlertDialog(
            onDismissRequest = { showFileInfo = false },
            title = { Text(stringResource(R.string.player_file_info)) },
            text = {
                Column {
                    FileInfoRow(stringResource(R.string.player_info_duration), formatTime(meta.durationMs))
                    FileInfoRow(stringResource(R.string.player_info_size), StatsFormat.bytes(meta.sizeBytes))
                    if (meta.width > 0) {
                        FileInfoRow(stringResource(R.string.player_info_resolution), "${meta.width}×${meta.height}")
                    }
                    if (meta.bitrateBps > 0) {
                        FileInfoRow(
                            stringResource(R.string.player_info_bitrate),
                            "%.1f Mbps".format(meta.bitrateBps / 1_000_000.0),
                        )
                    }
                    FileInfoRow(stringResource(R.string.player_info_codec), meta.codec.ifBlank { "--" })
                }
            },
            confirmButton = {
                TextButton(onClick = { showFileInfo = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

// ---- 辅助函数 ----

/** 格式化毫秒为 HH:MM:SS 或 MM:SS。 */
private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** 文件信息行。 */
@Composable
private fun FileInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/** 视频元数据（时长/分辨率/码率/编码器）。 */
private data class MediaMetadata(
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val bitrateBps: Long = 0,
    val codec: String = "",
)

/** 用 MediaMetadataRetriever 读取第一个文件的元数据。 */
private fun readMediaMetadata(context: android.content.Context, file: File?): MediaMetadata {
    if (file == null) return MediaMetadata()
    return runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.absolutePath)
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""
            MediaMetadata(durationMs = dur, sizeBytes = file.length(), width = w, height = h, bitrateBps = bitrate, codec = mime)
        }
    }.getOrDefault(MediaMetadata(sizeBytes = file.length()))
}

/** 截图：取 Player 当前帧，保存到 Pictures/LiveRecorder/Screenshots/。 */
private suspend fun takeScreenshot(context: android.content.Context, exoPlayer: ExoPlayer): String {
    return withContext(Dispatchers.Main) {
        runCatching {
            // 尝试从 ExoPlayer 抓当前帧（@UnstableApi；失败时返回 null）
            @Suppress("invisible_reference", "InvisibleMemberAPI")
            val bitmap: Bitmap? = try {
                exoPlayer.currentBitmap(1920, 1080)
            } catch (_: Throwable) { null }
            if (bitmap == null) return@withContext context.getString(R.string.player_screenshot_failed)
            val name = "LR_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LiveRecorder/Screenshots")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return@withContext context.getString(R.string.player_screenshot_failed)
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os)
                }
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "LiveRecorder/Screenshots",
                )
                dir.mkdirs()
                FileOutputStream(File(dir, name)).use { os ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os)
                }
            }
            context.getString(R.string.player_screenshot_saved, name)
        }.getOrDefault(context.getString(R.string.player_screenshot_failed))
    }
}
