package com.wochatchat.liverecorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wochatchat.liverecorder.RecorderApp
import com.wochatchat.liverecorder.data.MonitorStore
import com.wochatchat.liverecorder.recorder.RecordController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    private val store = MonitorStore(app)
    private val controller = (app as RecorderApp).recordController

    val urls: StateFlow<List<String>> = store.urls
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** url → 录制状态（Resolving/Recording/Finished/Failed）。 */
    val recordStates: StateFlow<Map<String, RecordController.RecordState>> = controller.states

    fun add(url: String) = viewModelScope.launch { store.add(url) }

    fun remove(url: String) = viewModelScope.launch {
        controller.stop(url)
        store.remove(url)
    }

    fun startRecord(url: String) = controller.start(url)

    fun stopRecord(url: String) = controller.stop(url)
}
