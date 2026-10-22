package com.example.ble_device_mesh.data

/**
 * OC6701 PWM 驱动的亮度补偿映射。
 *
 * - UI 百分比：用户在 App 上看到的 0-100%（路灯曲线、定时任务滑块等）
 * - 设备百分比：实际下发给固件的补偿值（固件再线性换算成 PWM 0-255）
 *
 * 两个方向集中在这里，避免各页面各自换算导致显示不一致。
 */
object BrightnessMap {

    private val OC6701_TABLE = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1,
        1, 1, 1, 2, 2, 3, 3, 4, 5, 6,
        8, 9, 10, 12, 13, 15, 17, 19, 21, 23,
        25, 28, 30, 33, 36, 39, 42, 45, 48, 52,
        55, 59, 62, 66, 70, 74, 78, 83, 87, 92,
        96, 100
    )

    /** UI 百分比 → 设备百分比 */
    fun map(uiBrightness: Int): Int {
        val clamped = uiBrightness.coerceIn(0, 100)
        return OC6701_TABLE[(clamped + 1) / 2]
    }

    /**
     * 设备百分比 → UI 百分比。
     * 映射表是多对一（如 UI 49/50% 都对应 15%），取最接近的 UI 值；
     * 有多个等距候选时取较大的那个，这样 50%、100% 这类整值能原样还原。
     */
    fun unmap(deviceBrightness: Int): Int {
        val target = deviceBrightness.coerceIn(0, 100)
        // 0 表示关灯（UI 0~15% 都会映射成 0），直接返回 0
        if (target == 0) return 0
        var best = 0
        var bestDiff = Int.MAX_VALUE
        for (ui in 0..100) {
            val diff = kotlin.math.abs(map(ui) - target)
            if (diff < bestDiff || (diff == bestDiff && ui > best)) {
                bestDiff = diff
                best = ui
            }
        }
        return best
    }
}
