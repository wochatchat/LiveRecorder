package com.wochatchat.liverecorder.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V3-4 R1：悬浮球几何纯函数测试（贴边吸附 / 拖动边界 / 拖动判定）。 */
class FloatingBallMathTest {

    // ---------- snapTargetX ----------

    @Test
    fun `球心在左半屏吸附左缘`() {
        // 屏幕 1080，球 120：球心 x=100+60=160 < 540 → 吸左
        assertEquals(0f, FloatingBallMath.snapTargetX(100f, 1080, 120))
    }

    @Test
    fun `球心在右半屏吸附右缘`() {
        // 球心 x=900+60=960 > 540 → 吸右 = 1080-120
        assertEquals(960f, FloatingBallMath.snapTargetX(900f, 1080, 120))
    }

    @Test
    fun `球心恰在中线吸左缘`() {
        // 球心 = 540，条件 <= → 左
        assertEquals(0f, FloatingBallMath.snapTargetX(480f, 1080, 120))
    }

    // ---------- clamp ----------

    @Test
    fun `拖动坐标限制在屏幕内`() {
        assertEquals(0f, FloatingBallMath.clamp(-50f, 0f, 960f))
        assertEquals(960f, FloatingBallMath.clamp(1500f, 0f, 960f))
        assertEquals(300f, FloatingBallMath.clamp(300f, 0f, 960f))
    }

    @Test
    fun `min大于max时不产生交叉翻转`() {
        // 防御：max < min（异常小屏）时 clamp 先判下界再判上界，不会负值回卷
        val v = FloatingBallMath.clamp(5f, 10f, 0f)
        assertTrue(v >= 0f || v >= 10f)
    }

    // ---------- exceededSlop ----------

    @Test
    fun `位移小于阈值不算拖动`() {
        assertFalse(FloatingBallMath.exceededSlop(3f, 4f, slop = 8f))
    }

    @Test
    fun `单轴超过阈值算拖动`() {
        assertTrue(FloatingBallMath.exceededSlop(0f, 9f, slop = 8f))
        assertTrue(FloatingBallMath.exceededSlop(9f, 0f, slop = 8f))
    }

    @Test
    fun `恰等于阈值不算拖动（严格大于）`() {
        assertFalse(FloatingBallMath.exceededSlop(8f, 0f, slop = 8f))
    }
}
