package com.example.ble_device_mesh

import android.util.Log
import com.example.ble_device_mesh.data.SchedulerTask
import no.nordicsemi.android.mesh.ApplicationKey
import no.nordicsemi.android.mesh.data.GenericTransitionTime
import no.nordicsemi.android.mesh.data.ScheduleEntry
import no.nordicsemi.android.mesh.transport.MeshMessage
import no.nordicsemi.android.mesh.transport.SchedulerGet
import no.nordicsemi.android.mesh.transport.SchedulerActionGet
import no.nordicsemi.android.mesh.transport.SchedulerActionSet

/**
 * Scheduler 消息辅助类
 * 使用 nRF Mesh 库 3.4.0 的标准 Scheduler 模型类
 *
 * 标准 10 字节编码（Mesh Model Specification）：
 * Byte 0: Index (4 bits)
 * Byte 1-2: Year (16 bits)
 * Byte 3-6: Month/Day/Hour/Minute/Second/DayOfWeek 位域
 * Byte 7: Brightness (0-100) - 固件自定义，使用 TransitionTime 字段传递
 * Byte 8: Enabled (0/1) - 固件自定义，使用 SceneNumber 低字节传递
 * Byte 9: 保留 (0) - SceneNumber 高字节
 *
 * 字段映射说明：
 * - action: TurnOn(1) / TurnOff(0) / NoAction(15)
 * - brightness: 映射到 TransitionTime (Byte 7)
 * - enabled: 映射到 SceneNumber 低字节 (Byte 8)
 * - repeat(dayOfWeek): 标准 DayOfWeek 位掩码
 */
object SchedulerMessageHelper {

    private const val TAG = "SchedulerHelper"

    /**
     * 创建 SchedulerGet 消息
     * OpCode: 0x8249
     */
    fun createSchedulerGet(appKey: ApplicationKey): MeshMessage {
        return SchedulerGet(appKey)
    }

    /**
     * 创建 SchedulerActionGet 消息
     * OpCode: 0x8248
     */
    fun createSchedulerActionGet(appKey: ApplicationKey, index: Int): MeshMessage {
        return SchedulerActionGet(appKey, index)
    }

    /**
     * 创建 SchedulerActionSet 消息
     * OpCode: 0x60
     * 使用 nRF Mesh 库的标准 ScheduleEntry 构造消息
     */
    fun createSchedulerActionSet(appKey: ApplicationKey, task: SchedulerTask): MeshMessage {
        Log.d(TAG, "创建 SchedulerActionSet: index=${task.index}, time=${task.getTimeString()}, action=${task.action}, brightness=${task.brightness}%, repeat=0x${task.repeat.toString(16)}, enabled=${task.enabled}")

        val entry = buildScheduleEntry(task)
        val message = SchedulerActionSet(appKey, task.index, entry)

        Log.d(TAG, "SchedulerActionSet 创建成功, opcode=0x${message.opCode.toString(16)}")
        return message
    }

    /**
     * 将 SchedulerTask 转换为标准 ScheduleEntry
     *
     * 注意：ScheduleEntry 内部类的工厂方法（Hour.Value, Minute.Value, DayOfWeek.Any 等）
     * 通过 ScheduleEntryFactory (Java) 调用，因为 Kotlin 2.0 K2 编译器无法直接访问
     * 这些 Java 静态方法（私有基类 EntryType 导致的可见性问题）。
     *
     * 固件自定义字段映射：
     * - Byte 7: Brightness (0-100) - 使用 TransitionTime 字段传递
     * - Byte 8: Enabled (0/1) - 使用 SceneNumber 低字节传递
     * - Byte 9: 保留 (0) - SceneNumber 高字节
     */
    private fun buildScheduleEntry(task: SchedulerTask): ScheduleEntry {
        val entry = ScheduleEntry()

        // Year: Any=任意年份, Specific=指定年份
        entry.setYear(ScheduleEntryFactory.createYear(task.year))

        // Month: Any=任意月份
        entry.setMonth(ScheduleEntryFactory.createMonthAll())

        // Day: Any=任意日期
        entry.setDay(ScheduleEntryFactory.createDayAny())

        // Hour
        entry.setHour(ScheduleEntryFactory.createHour(task.hour))

        // Minute
        entry.setMinute(ScheduleEntryFactory.createMinute(task.minute))

        // Second: 固定为 0
        entry.setSecond(ScheduleEntryFactory.createSecond(0))

        // DayOfWeek: 标准 7 位位掩码 (bit0=Sun..bit6=Sat)
        // 0x7F = Any, 0 = 单次, 其他 = 指定星期
        entry.setDayOfWeek(ScheduleEntryFactory.createDayOfWeek(task.repeat))

        // Action: 始终使用实际的 action，不管 enabled 状态
        // enabled 状态通过 Byte 8 传递
        val actionValue = when (task.action) {
            SchedulerTask.Action.ON -> ScheduleEntryFactory.getActionTurnOn()
            SchedulerTask.Action.OFF -> ScheduleEntryFactory.getActionTurnOff()
            else -> ScheduleEntryFactory.getActionNoAction()
        }
        entry.setAction(actionValue)

        // Transition Time: 设为 Immediate (0)
        entry.setGenericTransitionTime(
            GenericTransitionTime(
                GenericTransitionTime.TransitionResolution.SECOND,
                GenericTransitionTime.TransitionStep.Immediate
            )
        )

        // Scene Number: 用于传递亮度值（固件自定义扩展）
        val sceneValue = task.brightness.coerceIn(0, 100)
        entry.setScene(ScheduleEntryFactory.createScene(sceneValue))

        Log.d(TAG, "ScheduleEntry 构建: action=$actionValue, scene=$sceneValue, dayOfWeek=${task.repeat}")
        return entry
    }

    /**
     * 解析 SchedulerStatus 消息
     * 返回 16 位 bitmask
     */
    fun parseSchedulerStatus(message: MeshMessage): Int? {
        Log.d(TAG, "=== 解析 SchedulerStatus ===")
        Log.d(TAG, "  消息类型: ${message.javaClass.simpleName}")
        Log.d(TAG, "  消息类全名: ${message.javaClass.name}")
        Log.d(TAG, "  OpCode: 0x${message.opCode.toString(16)}")

        try {
            // 尝试使用反射调用 getSchedules() 方法
            Log.d(TAG, "  尝试调用 getSchedules() 方法...")
            val method = message.javaClass.getMethod("getSchedules")
            val result = method.invoke(message) as? Int

            if (result != null) {
                Log.d(TAG, "  解析成功: bitmap = 0x${result.toString(16)}")

                // 显示哪些索引已设置
                val setIndexes = mutableListOf<Int>()
                for (i in 0..15) {
                    if ((result and (1 shl i)) != 0) {
                        setIndexes.add(i)
                    }
                }
                Log.d(TAG, "  已设置的索引: ${setIndexes.joinToString(", ")}")
                Log.d(TAG, "========================")
                return result
            } else {
                Log.w(TAG, "  getSchedules() 返回 null")
            }
        } catch (e: NoSuchMethodException) {
            Log.e(TAG, "  未找到 getSchedules() 方法")
            Log.e(TAG, "  可用方法列表:")
            message.javaClass.methods.forEach { method ->
                Log.e(TAG, "    - ${method.name}(${method.parameterTypes.joinToString { it.simpleName }}): ${method.returnType.simpleName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "  调用 getSchedules() 失败: ${e.message}")
            e.printStackTrace()
        }

        Log.d(TAG, "========================")
        return null
    }

    /**
     * 解析 SchedulerActionStatus 消息
     * 返回 SchedulerTask 对象
     */
    fun parseSchedulerActionStatus(message: MeshMessage, srcAddress: Int = 0): SchedulerTask? {
        Log.d(TAG, "=== 解析 SchedulerActionStatus ===")
        Log.d(TAG, "  消息类型: ${message.javaClass.simpleName}")
        Log.d(TAG, "  消息类全名: ${message.javaClass.name}")
        Log.d(TAG, "  OpCode: 0x${message.opCode.toString(16)}")

        try {
            // 获取 index
            Log.d(TAG, "  尝试获取 index...")
            val indexMethod = message.javaClass.getMethod("getIndex")
            val index = indexMethod.invoke(message) as? Int
            if (index == null) {
                Log.w(TAG, "  getIndex() 返回 null")
                return null
            }
            Log.d(TAG, "  index = $index")

            // 获取 ScheduleEntry
            Log.d(TAG, "  尝试获取 ScheduleEntry...")
            val entryMethod = message.javaClass.getMethod("getEntry")
            val entry = entryMethod.invoke(message)
            if (entry == null) {
                Log.w(TAG, "  getEntry() 返回 null")
                return null
            }
            Log.d(TAG, "  ScheduleEntry 类型: ${entry.javaClass.simpleName}")

            val task = convertScheduleEntry(entry, index, srcAddress)
            if (task != null) {
                Log.d(TAG, "  解析成功: ${task.getTimeString()}, action=${task.action}, brightness=${task.brightness}%")
            } else {
                Log.w(TAG, "  convertScheduleEntry 返回 null")
            }
            Log.d(TAG, "========================")
            return task
        } catch (e: NoSuchMethodException) {
            Log.e(TAG, "  未找到方法: ${e.message}")
            Log.e(TAG, "  可用方法列表:")
            message.javaClass.methods.forEach { method ->
                Log.e(TAG, "    - ${method.name}(${method.parameterTypes.joinToString { it.simpleName }}): ${method.returnType.simpleName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "  解析失败: ${e.message}")
            e.printStackTrace()
        }

        Log.d(TAG, "========================")
        return null
    }

    /**
     * 将 nRF Mesh 库的 ScheduleEntry 转换为 SchedulerTask
     *
     * 固件自定义字段映射：
     * - SceneNumber: 亮度值 (0-100)
     * - Action: NoAction = 禁用
     */
    private fun convertScheduleEntry(entry: Any, index: Int, srcAddress: Int): SchedulerTask? {
        try {
            val clazz = entry.javaClass

            // 读取各字段值
            val year = getEnumValue(clazz.getMethod("getYear").invoke(entry))
            val month = getEnumValue(clazz.getMethod("getMonth").invoke(entry))
            val day = getEnumValue(clazz.getMethod("getDay").invoke(entry))
            val hour = getEnumValue(clazz.getMethod("getHour").invoke(entry))
            val minute = getEnumValue(clazz.getMethod("getMinute").invoke(entry))
            val second = getEnumValue(clazz.getMethod("getSecond").invoke(entry))
            val dayOfWeek = getEnumValue(clazz.getMethod("getDayOfWeek").invoke(entry))
            val actionValue = getEnumValue(clazz.getMethod("getAction").invoke(entry))
            val sceneValue = getEnumValue(clazz.getMethod("getScene").invoke(entry))

            Log.d(TAG, "转换 ScheduleEntry: index=$index, hour=$hour, minute=$minute, action=$actionValue, scene=$sceneValue, dayOfWeek=$dayOfWeek")

            // Action 映射: NoAction(15) = 禁用
            val enabled = actionValue != 15
            val taskAction = when (actionValue) {
                1 -> SchedulerTask.Action.ON
                0 -> SchedulerTask.Action.OFF
                else -> SchedulerTask.Action.NO_ACTION
            }

            // 场景号映射回亮度（固件自定义扩展）
            val brightness = if (sceneValue == 0 || sceneValue == 0xFFFF) 0 else sceneValue.coerceIn(0, 100)

            return SchedulerTask(
                index = index,
                hour = hour,
                minute = minute,
                second = second,
                action = taskAction,
                brightness = brightness,
                repeat = dayOfWeek,  // dayOfWeek 就是位掩码
                enabled = enabled,
                year = if (year == 100) 0 else year,  // 100 = Any Year
                month = if (month == 0xFFF) 0 else month,
                day = if (day == 0) 0 else day,
                deviceAddress = srcAddress
            )
        } catch (e: Exception) {
            Log.e(TAG, "转换 ScheduleEntry 失败: ${e.message}")
            return null
        }
    }

    /**
     * 从枚举对象提取 value
     */
    private fun getEnumValue(enumObj: Any?): Int {
        if (enumObj == null) return 0
        return try {
            val method = enumObj.javaClass.getMethod("getValue")
            method.invoke(enumObj) as? Int ?: 0
        } catch (e: Exception) {
            0
        }
    }
}

/**
 * Scheduler Action 数据类（旧版兼容）
 */
data class SchedulerAction(
    val index: Int,
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val dayOfWeek: Int,
    val action: Int,
    val transitionTime: Int,
    val sceneNumber: Int
)
