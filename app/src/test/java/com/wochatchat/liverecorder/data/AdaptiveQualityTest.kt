package com.wochatchat.liverecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 10-10.2：带宽感知画质升降纯函数。 */
class AdaptiveQualityTest {

    @Test
    fun `WiFi 升一档`() {
        assertEquals("原画", AdaptiveQuality.adaptive("超清", NetType.WIFI))
        assertEquals("超清", AdaptiveQuality.adaptive("高清", NetType.WIFI))
        assertEquals("高清", AdaptiveQuality.adaptive("标清", NetType.WIFI))
    }

    @Test
    fun `流量降一档`() {
        assertEquals("超清", AdaptiveQuality.adaptive("原画", NetType.CELLULAR))
        assertEquals("标清", AdaptiveQuality.adaptive("高清", NetType.CELLULAR))
        assertEquals("流畅", AdaptiveQuality.adaptive("标清", NetType.CELLULAR))
    }

    @Test
    fun `档位边界不越界`() {
        assertEquals("原画", AdaptiveQuality.adaptive("原画", NetType.WIFI))
        assertEquals("流畅", AdaptiveQuality.adaptive("流畅", NetType.CELLULAR))
    }

    @Test
    fun `无网络跟随基准`() {
        assertEquals("高清", AdaptiveQuality.adaptive("高清", NetType.NONE))
    }

    @Test
    fun `非法画质原样返回`() {
        assertEquals("自定义", AdaptiveQuality.adaptive("自定义", NetType.WIFI))
        assertEquals("", AdaptiveQuality.shift("", -1))
    }

    @Test
    fun `零步移动原样返回`() {
        assertEquals("高清", AdaptiveQuality.shift("高清", 0))
    }
}
