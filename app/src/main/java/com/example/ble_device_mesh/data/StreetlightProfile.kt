package com.example.ble_device_mesh.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 路灯模式曲线配置
 * 对应固件端路灯模式控制点
 *
 * 路灯模式占用 Scheduler 槽位 0-7，每个控制点占用一个槽位
 * 固件端每分钟线性插值计算当前亮度
 */
@Parcelize
data class StreetlightProfile(
    val deviceAddress: Int,       // 关联的设备 Mesh 地址
    val enabled: Boolean,         // 是否启用路灯模式
    val controlPoints: List<ControlPoint>  // 控制点列表(按时间排序)
) : Parcelable {

    /**
     * 控制点：定义一个(时间, 亮度)坐标
     */
    @Parcelize
    data class ControlPoint(
        val hour: Int,            // 0-23
        val minute: Int,          // 0-59
        val brightness: Int       // 0-100
    ) : Parcelable {
        /**
         * 转换为当天分钟数 (0-1439)
         */
        fun toMinutes(): Int = hour * 60 + minute

        /**
         * 获取时间显示字符串
         */
        fun getTimeString(): String = String.format("%02d:%02d", hour, minute)

        companion object {
            /**
             * 从分钟数创建控制点
             */
            fun fromMinutes(minutes: Int, brightness: Int): ControlPoint {
                val h = (minutes / 60) % 24
                val m = minutes % 60
                return ControlPoint(h, m, brightness)
            }
        }
    }

    /**
     * 将控制点转换为 SchedulerTask 列表
     * 路灯模式占用槽位 0-7，每个控制点占用一个槽位
     */
    fun toSchedulerTasks(): List<SchedulerTask> {
        return controlPoints.mapIndexed { index, point ->
            SchedulerTask(
                index = index.coerceIn(0, 7),  // 槽位 0-7
                hour = point.hour,
                minute = point.minute,
                second = 0,
                action = SchedulerTask.Action.STREETLIGHT,
                brightness = point.brightness.coerceIn(0, 100),
                repeat = 0x7F,  // 每天
                enabled = this.enabled,
                year = 0,
                month = 0,
                day = 0,
                deviceAddress = deviceAddress
            )
        }
    }

    /**
     * 获取曲线描述
     */
    fun getDescription(): String {
        if (controlPoints.isEmpty()) return "未设置"
        val sorted = controlPoints.sortedBy { it.toMinutes() }
        return sorted.joinToString(" → ") {
            "${it.getTimeString()} ${it.brightness}%"
        }
    }

    /**
     * 验证配置是否有效
     */
    fun isValid(): Boolean {
        return controlPoints.size >= 2 && controlPoints.size <= 8
    }

    /**
     * 获取当前时间对应的亮度（用于预览）
     * 线性插值计算
     */
    fun getBrightnessAt(hour: Int, minute: Int): Int {
        if (controlPoints.size < 2) return 0

        val currentMinutes = hour * 60 + minute
        val sorted = controlPoints.sortedBy { it.toMinutes() }

        // 早于第一个点
        if (currentMinutes <= sorted.first().toMinutes()) {
            return sorted.first().brightness
        }

        // 晚于最后一个点
        if (currentMinutes >= sorted.last().toMinutes()) {
            return sorted.last().brightness
        }

        // 找到所在区间
        for (i in 0 until sorted.size - 1) {
            val start = sorted[i]
            val end = sorted[i + 1]
            val startMin = start.toMinutes()
            val endMin = end.toMinutes()

            if (currentMinutes in startMin..endMin) {
                val span = endMin - startMin
                val elapsed = currentMinutes - startMin
                val diff = end.brightness - start.brightness
                return start.brightness + (diff * elapsed + span / 2) / span
            }
        }

        return sorted.last().brightness
    }

    companion object {
        /**
         * 从 SchedulerTask 列表中解析出路灯模式曲线
         */
        fun fromSchedulerTasks(tasks: List<SchedulerTask>, deviceAddress: Int): StreetlightProfile? {
            val streetlightTasks = tasks.filter {
                (it.action == SchedulerTask.Action.STREETLIGHT || it.action == SchedulerTask.Action.ON) && it.index in 0..7
            }

            if (streetlightTasks.isEmpty()) return null

            val points = streetlightTasks
                .sortedBy { it.index }
                .map {
                    ControlPoint(it.hour, it.minute, it.brightness)
                }

            return StreetlightProfile(
                deviceAddress = deviceAddress,
                enabled = streetlightTasks.all { it.enabled },
                controlPoints = points
            )
        }

        /**
         * 创建默认路灯模式配置
         * 18:00 100% → 20:00 60% → 22:00 30% → 23:00 10% → 06:00 0%
         */
        fun createDefault(deviceAddress: Int): StreetlightProfile {
            return StreetlightProfile(
                deviceAddress = deviceAddress,
                enabled = false,
                controlPoints = listOf(
                    ControlPoint(18, 0, 100),  // 18:00 100%
                    ControlPoint(20, 0, 60),   // 20:00 60%
                    ControlPoint(22, 0, 30),   // 22:00 30%
                    ControlPoint(23, 0, 10),   // 23:00 10%
                    ControlPoint(6, 0, 0)      // 06:00 关灯
                )
            )
        }
    }
}
