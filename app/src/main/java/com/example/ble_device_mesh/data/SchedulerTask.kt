package com.example.ble_device_mesh.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.time.LocalDate

/**
 * 定时任务数据模型
 * 对应固件 scheduler_entry_t 结构体
 */
@Parcelize
data class SchedulerTask(
    val index: Int,              // 0-15
    val hour: Int,               // 0-23
    val minute: Int,             // 0-59
    val second: Int = 0,         // 0-59 (通常为0，分钟精度)
    val action: Action,          // ON/OFF
    val brightness: Int,         // 0-100
    val repeat: Int,             // bit0-6: 周日-周六, 0x00=一次性
    val enabled: Boolean,
    val year: Int = 0,           // 一次性任务的年份
    val month: Int = 0,          // 一次性任务的月份 (1-12)
    val day: Int = 0,            // 一次性任务的日期 (1-31)
    val deviceAddress: Int = 0   // 关联的设备 Mesh 地址
) : Parcelable {

    enum class Action(val value: Int) {
        OFF(0x00),
        ON(0x01),
        STREETLIGHT(0x03),  // 路灯模式控制点
        NO_ACTION(0x0F);

        companion object {
            fun fromValue(value: Int): Action {
                return when (value) {
                    0x00 -> OFF
                    0x01 -> ON
                    0x03 -> STREETLIGHT
                    0x0F -> NO_ACTION
                    else -> OFF
                }
            }
        }
    }

    /**
     * 获取重复规则描述
     */
    fun getRepeatDescription(): String {
        return when (repeat) {
            0x00 -> {
                if (year > 0 && month > 0 && day > 0) {
                    "一次性 ($year-${String.format("%02d", month)}-${String.format("%02d", day)})"
                } else {
                    "一次性"
                }
            }
            0x7F -> "每天"
            0x3E -> "工作日"
            0x41 -> "周末"
            else -> {
                val days = listOf("日", "一", "二", "三", "四", "五", "六")
                days.filterIndexed { i, _ -> (repeat and (1 shl i)) != 0 }
                    .joinToString("、") { "周$it" }
            }
        }
    }

    /**
     * 获取时间显示字符串
     */
    fun getTimeString(): String {
        return String.format("%02d:%02d", hour, minute)
    }

    /**
     * 获取动作描述
     */
    fun getActionDescription(): String {
        return when (action) {
            Action.ON -> "开灯 ${brightness}%"
            Action.OFF -> "关灯"
            Action.STREETLIGHT -> "路灯 ${brightness}%"
            Action.NO_ACTION -> "未设置"
        }
    }

    /**
     * 获取一次性任务日期
     */
    fun getDate(): LocalDate? {
        return if (repeat == 0x00 && year > 0 && month > 0 && day > 0) {
            try { LocalDate.of(year, month, day) } catch (e: Exception) { null }
        } else null
    }

    companion object {
        const val NO_ACTION = 0x0F
    }
}
