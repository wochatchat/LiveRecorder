package com.wochatchat.liverecorder.data

/**
 * Phase 10-10.2：带宽感知画质升降（纯函数便于单测）。
 * WiFi 下自动升一档画质，流量下自动降一档；仅对「跟随全局画质」的条目生效，
 * 单条画质覆盖（PerUrlSettings.quality）优先级更高，不受影响。
 */
enum class NetType { WIFI, CELLULAR, NONE }

object AdaptiveQuality {
    /** 画质档位（高→低），与 PerUrlSettings.VALID_QUALITIES 同序。 */
    val LADDER = listOf("原画", "超清", "高清", "标清", "流畅")

    /**
     * 沿档位移动 [steps] 步：负数=升画质（更清晰），正数=降画质（更省流量）。
     * 基准值不在档位内（非法/自定义）时原样返回。
     */
    fun shift(base: String, steps: Int): String {
        val idx = LADDER.indexOf(base)
        if (idx < 0 || steps == 0) return base
        val next = (idx + steps).coerceIn(0, LADDER.lastIndex)
        return LADDER[next]
    }

    /** 按网络类型得出实际画质：WiFi 升一档，流量降一档，无网络/未知跟随基准。 */
    fun adaptive(base: String, net: NetType): String = when (net) {
        NetType.WIFI -> shift(base, -1)
        NetType.CELLULAR -> shift(base, +1)
        NetType.NONE -> base
    }
}
