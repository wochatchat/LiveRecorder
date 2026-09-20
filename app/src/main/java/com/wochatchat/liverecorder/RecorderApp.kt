package com.wochatchat.liverecorder

import android.app.Application
import com.wochatchat.liverecorder.recorder.RecordController
import java.io.File

/** 进程级录制控制器（应用销毁前常驻，独立于 Activity 生命周期）。 */
class RecorderApp : Application() {

    lateinit var recordController: RecordController
        private set

    override fun onCreate() {
        super.onCreate()
        recordController = RecordController(baseDir = File(filesDir, "downloads"))
    }
}
