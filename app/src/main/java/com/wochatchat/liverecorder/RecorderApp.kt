package com.wochatchat.liverecorder

import android.app.Application
import com.wochatchat.liverecorder.monitor.MonitorLoop
import com.wochatchat.liverecorder.platform.douyin.DouyinSpider
import com.wochatchat.liverecorder.recorder.RecordController
import com.wochatchat.liverecorder.service.EventNotifier
import java.io.File

/** 进程级录制控制器（应用销毁前常驻，独立于 Activity 生命周期）。 */
class RecorderApp : Application() {

    lateinit var recordController: RecordController
        private set

    lateinit var monitorLoop: MonitorLoop
        private set

    override fun onCreate() {
        super.onCreate()
        // 事件渠道尽早创建（2e：开播/关播通知）
        val notifier = EventNotifier(this)
        notifier.createChannel()
        val spider = DouyinSpider()
        recordController = RecordController(baseDir = File(filesDir, "downloads"), fetchInfo = { spider.fetchStreamInfo(it) })
        monitorLoop = MonitorLoop(
            check = { url -> spider.fetchStreamInfo(url) },
            isRecording = { url -> recordController.isActive(url) },
            onLive = { url, _ -> recordController.start(url) },
            onLiveEvent = { url, anchor, title -> notifier.notifyLive(url, anchor, title) },
            onOfflineEvent = { url, anchor -> notifier.notifyOffline(url, anchor) },
        )
    }
}
