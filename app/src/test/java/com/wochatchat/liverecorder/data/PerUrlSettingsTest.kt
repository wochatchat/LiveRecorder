package com.wochatchat.liverecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4-4.1 + 10-10.1：单条参数覆盖 JSON 往返（含 audioOnly）。 */
class PerUrlSettingsTest {

    @Test
    fun `audioOnly 往返序列化`() {
        val s = PerUrlSettings(audioOnly = true)
        val json = s.toJson()
        assertEquals(true, json.optBoolean("audio_only"))
        val back = PerUrlSettings.fromJson(json)
        assertEquals(true, back.audioOnly)
    }

    @Test
    fun `未设置 audioOnly 序列化不含该键`() {
        val json = PerUrlSettings(quality = "超清").toJson()
        assertFalse(json.has("audio_only"))
        assertNull(PerUrlSettings.fromJson(json).audioOnly)
    }

    @Test
    fun `audioOnly false 覆盖参与 isEmpty 判定`() {
        assertTrue(PerUrlSettings().isEmpty)
        assertFalse(PerUrlSettings(audioOnly = false).isEmpty)
    }

    @Test
    fun `全字段往返不丢失`() {
        val s = PerUrlSettings(
            quality = "高清",
            saveFormat = "mp4",
            segmented = false,
            segmentTimeSec = 600,
            loopIntervalSec = 120,
            audioOnly = true,
        )
        val back = PerUrlSettings.fromJson(s.toJson())
        assertEquals(s, back)
    }
}
