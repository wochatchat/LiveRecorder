package com.wochatchat.liverecorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.RecorderApp
import com.wochatchat.liverecorder.data.RecordHistoryEntry
import com.wochatchat.liverecorder.storage.StorageUsage
import com.wochatchat.liverecorder.ui.components.platformKeyForUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

/**
 * 录制记录页 ViewModel（6e R19）：读取 [RecordHistoryStore]（经 RecorderApp 单例落库），
 * 提供筛选（全部/今日 + 平台）、统计与删除（文件 + 记录）。
 */
class RecordsViewModel(app: Application) : AndroidViewModel(app) {

    private val historyStore = (app as RecorderApp).historyStore

    /** 全部记录（结束时间倒序）。 */
    val allEntries: StateFlow<List<RecordHistoryEntry>> = historyStore.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- 筛选 ----

    /** 筛选器：时间段（全部/今日）+ 平台（null=不限）。 */
    data class RecordFilter(
        val todayOnly: Boolean = false,
        val platformKey: String? = null,
    )

    private val _filter = MutableStateFlow(RecordFilter())
    val filter: StateFlow<RecordFilter> = _filter.asStateFlow()

    fun setTodayOnly(todayOnly: Boolean) {
        _filter.value = _filter.value.copy(todayOnly = todayOnly)
    }

    fun setPlatformKey(platformKey: String?) {
        _filter.value = _filter.value.copy(platformKey = platformKey)
    }

    /** 筛选后的记录（列表页主数据源）。 */
    val filtered: StateFlow<List<RecordHistoryEntry>> =
        combine(allEntries, _filter) { list, f ->
            val todayStart = if (f.todayOnly) startOfTodayMs() else 0L
            list.filter { e ->
                (todayStart == 0L || e.endTimeMs >= todayStart) &&
                    (f.platformKey == null || platformKeyForUrl(e.url) == f.platformKey)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- 统计（R19 顶部统计行） ----

    /** 今日录制条数 + 全部记录合计字节数。 */
    data class RecordStats(val todayCount: Int, val totalBytes: Long)

    val stats: StateFlow<RecordStats> = allEntries.map { list ->
        val todayStart = startOfTodayMs()
        RecordStats(
            todayCount = list.count { it.endTimeMs >= todayStart },
            totalBytes = list.sumOf { it.bytes },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecordStats(0, 0))

    // ---- 存储用量（R20：页顶进度条） ----

    /** 保存目录分区存储用量，30s 轮询刷新。 */
    val storageUsage: StateFlow<StorageUsage> = flow {
        while (true) {
            val storage = (getApplication() as RecorderApp).storage
            emit(StorageUsage(freeGb = storage.freeGb(), totalGb = storage.totalGb()))
            delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StorageUsage(0.0, 0.0))

    // ---- 删除 ----

    private val _deleteResult = MutableStateFlow<String?>(null)

    /** 删除结果（一次性，UI 消费后调 [consumeDeleteResult] 清除）。 */
    val deleteResult: StateFlow<String?> = _deleteResult.asStateFlow()

    /** 删除记录：文件（或分段目录）递归删除 + 记录移除。 */
    fun delete(entry: RecordHistoryEntry) {
        viewModelScope.launch {
            val fileMsg = runCatching {
                val f = File(entry.savePath)
                if (f.exists() && !f.deleteRecursively()) getApplication<Application>().getString(R.string.records_delete_failed)
                else null
            }.getOrDefault(getApplication<Application>().getString(R.string.records_delete_error))
            historyStore.remove(entry)
            _deleteResult.value = fileMsg ?: getApplication<Application>().getString(R.string.records_delete_done, entry.savePath.substringAfterLast('/'))
        }
    }

    fun consumeDeleteResult() {
        _deleteResult.value = null
    }

    private fun startOfTodayMs(): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
