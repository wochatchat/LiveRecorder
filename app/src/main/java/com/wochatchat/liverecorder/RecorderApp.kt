package com.wochatchat.liverecorder

import android.app.Application
import com.wochatchat.liverecorder.monitor.MonitorLoop
import com.wochatchat.liverecorder.platform.douyin.DouyinSpider
import com.wochatchat.liverecorder.recorder.RecordController
import java.io.File

/** 进程级录制控制器（应用销毁前常驻，独立于 Activity 生命周期）。 */
class RecorderApp : Application() {

    lateinit var recordController: RecordController
        private set

    lateinit var monitorLoop: MonitorLoop
        private set

    override fun onCreate() {
        super.onCreate()
        val spider = DouyinSpider()
        recordController = RecordController(baseDir = File(filesDir, "downloads"), spider = spider)
        monitorLoop = MonitorLoop(check = { url -> spider.fetchStreamInfo(url) })
    }
}
