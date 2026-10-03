package com.wochatchat.liverecorder.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.data.PerUrlSettings

/**
 * Phase 4-4.1：单条监控条目的录制参数覆盖 Bottom Sheet。
 * 从 MonitorCard 的「参数」按钮触发；无覆盖时展示空表单（全局值预填），
 * 有覆盖时展示已填值（可编辑或全部清除恢复跟随全局）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerUrlSettingsSheet(
    url: String,
    current: PerUrlSettings?,
    globalSettings: com.wochatchat.liverecorder.data.AppSettings,
    sheetState: SheetState,
    onSave: (PerUrlSettings?) -> Unit,
    onDismiss: () -> Unit,
) {
    var quality by remember(current) { mutableStateOf(current?.quality ?: "") }
    var saveFormat by remember(current) { mutableStateOf(current?.saveFormat ?: "") }
    var hasSegmented by remember(current) { mutableStateOf(current?.segmented != null) }
    var segmented by remember(current) { mutableStateOf(current?.segmented ?: globalSettings.segmented) }
    var segmentTimeText by remember(current) {
        mutableStateOf(current?.segmentTimeSec?.toString() ?: globalSettings.segmentTimeSec.toString())
    }
    var loopIntervalText by remember(current) {
        mutableStateOf(current?.loopIntervalSec?.toString() ?: globalSettings.loopIntervalSec.toString())
    }
    var audioOnly by remember(current) { mutableStateOf(current?.audioOnly == true) }

    ModalBottomSheet(sheetState = sheetState, onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                stringResource(R.string.perurl_settings_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.perurl_reset_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(12.dp))

            // ---- 画质覆盖 ----
            Text(stringResource(R.string.perurl_quality_label), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.perurl_global_current, globalSettings.quality),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PerUrlSettings.VALID_QUALITIES.forEach { q ->
                    FilterChip(
                        selected = quality == q,
                        onClick = { quality = if (quality == q) "" else q },
                        label = { Text(q, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---- 保存格式覆盖 ----
            Text(stringResource(R.string.perurl_saveformat_label), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.perurl_global_current, globalSettings.saveFormat),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PerUrlSettings.VALID_SAVE_FORMATS.forEach { fmt ->
                    FilterChip(
                        selected = saveFormat == fmt,
                        onClick = { saveFormat = if (saveFormat == fmt) "" else fmt },
                        label = { Text(fmt.uppercase(), style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---- 分段开关 + 分段时间覆盖 ----
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.perurl_segmented_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.perurl_global_current, if (globalSettings.segmented) "开" else "关"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = hasSegmented,
                    onCheckedChange = {
                        hasSegmented = it
                        if (!it) segmented = globalSettings.segmented
                    },
                )
            }
            if (hasSegmented) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.perurl_segmented_value),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = segmented, onCheckedChange = { segmented = it })
                }
                if (segmented) {
                    OutlinedTextField(
                        value = segmentTimeText,
                        onValueChange = { segmentTimeText = it },
                        label = { Text(stringResource(R.string.perurl_segment_time)) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        supportingText = {
                            Text(
                                stringResource(R.string.perurl_global_current, "${globalSettings.segmentTimeSec}s"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---- Phase 10-10.1：仅录制音频 m4a 覆盖 ----
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.perurl_audioonly_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.perurl_audioonly_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = audioOnly,
                    onCheckedChange = { audioOnly = it },
                )
            }
            Spacer(Modifier.height(12.dp))

            // ---- 循环时间（监控间隔）覆盖 ----
            OutlinedTextField(
                value = loopIntervalText,
                onValueChange = { loopIntervalText = it },
                label = { Text(stringResource(R.string.perurl_loop_interval)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number
                ),
                supportingText = {
                    Text(
                        stringResource(R.string.perurl_global_current, "${globalSettings.loopIntervalSec}s"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            Spacer(Modifier.height(16.dp))

            // ---- 底部操作行 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { onSave(null) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.perurl_clear_all))
                }
                Button(
                    onClick = {
                        val segTime = segmentTimeText.toIntOrNull()
                        val loopInt = loopIntervalText.toLongOrNull()
                        val built = PerUrlSettings(
                            quality = quality.takeIf { it.isNotBlank() },
                            saveFormat = saveFormat.takeIf { it.isNotBlank() },
                            segmented = if (hasSegmented) segmented else null,
                            segmentTimeSec = if (hasSegmented && segmented) segTime else null,
                            loopIntervalSec = loopInt,
                            audioOnly = if (audioOnly) true else null,
                        )
                        if (built.validate() != null) return@Button
                        onSave(built)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
