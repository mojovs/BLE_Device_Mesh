package com.example.ble_device_mesh

import android.util.Log
import no.nordicsemi.android.mesh.ApplicationKey
import no.nordicsemi.android.mesh.transport.MeshMessage
import no.nordicsemi.android.mesh.transport.AccessMessage
import no.nordicsemi.android.mesh.transport.GenericOnOffGet
import no.nordicsemi.android.mesh.transport.VendorModelMessageUnacked
import java.lang.reflect.Constructor

/**
 * Scheduler 消息辅助类
 * 用于创建和解析 Scheduler 相关的 Mesh 消息
 */
object SchedulerMessageHelper {

    /**
     * 创建 SchedulerGet 消息
     * 尝试使用反射创建自定义 AccessMessage
     */
    fun createSchedulerGet(appKey: ApplicationKey): MeshMessage? {
        return try {
            // 尝试使用新版本库的 SchedulerGet（如果存在）
            try {
                val schedulerGetClass = Class.forName("no.nordicsemi.android.mesh.transport.SchedulerGet")
                val constructor = schedulerGetClass.getConstructor(ApplicationKey::class.java)
                Log.d("SchedulerHelper", "使用标准 SchedulerGet 类")
                return constructor.newInstance(appKey) as MeshMessage
            } catch (e: ClassNotFoundException) {
                Log.w("SchedulerHelper", "nRF Mesh 库不支持 SchedulerGet，使用 GenericOnOffGet 代替")
                GenericOnOffGet(appKey)
            }
        } catch (e: Exception) {
            Log.e("SchedulerHelper", "创建消息失败: ${e.message}")
            null
        }
    }

    /**
     * 创建 SchedulerActionGet 消息
     */
    fun createSchedulerActionGet(appKey: ApplicationKey, index: Int): MeshMessage? {
        return try {
            val params = ByteArray(1).apply {
                this[0] = (index and 0x0F).toByte()
            }
            createCustomAccessMessage(appKey, 0x8248, params)
        } catch (e: Exception) {
            Log.e("SchedulerHelper", "创建 SchedulerActionGet 消息失败: ${e.message}")
            null
        }
    }

    /**
     * 使用反射创建自定义 AccessMessage
     */
    private fun createCustomAccessMessage(appKey: ApplicationKey, opCode: Int, parameters: ByteArray): MeshMessage? {
        return try {
            // 使用 GenericOnOffGet 作为模板
            val message = GenericOnOffGet(appKey)

            Log.d("SchedulerHelper", "创建自定义消息: OpCode=0x${opCode.toString(16)}, 参数长度=${parameters.size}")

            // 尝试通过反射修改 OpCode
            try {
                val opCodeField = AccessMessage::class.java.getDeclaredField("mOpCode")
                opCodeField.isAccessible = true
                opCodeField.set(message, opCode)
                Log.d("SchedulerHelper", "成功设置 OpCode: 0x${opCode.toString(16)}")
            } catch (e: Exception) {
                Log.w("SchedulerHelper", "无法设置 mOpCode 字段: ${e.message}")
                // 尝试其他可能的字段名
                try {
                    val opCodeField = AccessMessage::class.java.getDeclaredField("opCode")
                    opCodeField.isAccessible = true
                    opCodeField.set(message, opCode)
                    Log.d("SchedulerHelper", "成功设置 opCode: 0x${opCode.toString(16)}")
                } catch (e2: Exception) {
                    Log.e("SchedulerHelper", "无法设置 opCode 字段: ${e2.message}")
                }
            }

            // 如果有参数，尝试修改 parameters 字段
            if (parameters.isNotEmpty()) {
                try {
                    val paramsField = AccessMessage::class.java.getDeclaredField("mParameters")
                    paramsField.isAccessible = true
                    paramsField.set(message, parameters)
                    Log.d("SchedulerHelper", "成功设置参数: ${parameters.joinToString("") { "%02X".format(it) }}")
                } catch (e: Exception) {
                    Log.w("SchedulerHelper", "无法设置 mParameters 字段: ${e.message}")
                    // 尝试其他可能的字段名
                    try {
                        val paramsField = AccessMessage::class.java.getDeclaredField("parameters")
                        paramsField.isAccessible = true
                        paramsField.set(message, parameters)
                        Log.d("SchedulerHelper", "成功设置 parameters: ${parameters.joinToString("") { "%02X".format(it) }}")
                    } catch (e2: Exception) {
                        Log.e("SchedulerHelper", "无法设置 parameters 字段: ${e2.message}")
                    }
                }
            }

            Log.d("SchedulerHelper", "自定义消息创建成功")
            message
        } catch (e: Exception) {
            Log.e("SchedulerHelper", "创建自定义消息失败: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * 解析 SchedulerStatus 消息
     */
    fun parseSchedulerStatus(message: MeshMessage): Int? {
        try {
            if (message is AccessMessage) {
                val params = message.parameters
                if (params.size >= 2) {
                    return ((params[1].toInt() and 0xFF) shl 8) or (params[0].toInt() and 0xFF)
                }
            }
        } catch (e: Exception) {
            Log.e("SchedulerHelper", "解析 SchedulerStatus 失败: ${e.message}")
        }
        return null
    }

    /**
     * 解析 SchedulerActionStatus 消息
     */
    fun parseSchedulerActionStatus(message: MeshMessage): SchedulerAction? {
        try {
            if (message is AccessMessage) {
                val params = message.parameters
                if (params.size >= 10) {
                    return SchedulerAction(
                        index = params[0].toInt() and 0x0F,
                        year = params[1].toInt() and 0x7F,
                        month = (params[2].toInt() shr 4) and 0x0F,
                        day = params[2].toInt() and 0x1F,
                        hour = params[3].toInt() and 0x1F,
                        minute = params[4].toInt() and 0x3F,
                        second = params[5].toInt() and 0x3F,
                        dayOfWeek = params[6].toInt() and 0x7F,
                        action = params[7].toInt() and 0x0F,
                        transitionTime = params[8].toInt() and 0xFF,
                        sceneNumber = ((params[10].toInt() and 0xFF) shl 8) or (params[9].toInt() and 0xFF)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SchedulerHelper", "解析 SchedulerActionStatus 失败: ${e.message}")
        }
        return null
    }

    /**
     * 创建原始 Scheduler Get PDU 数据
     * OpCode: 0x8249 (2 字节)
     */
    fun createSchedulerGetPdu(): ByteArray {
        return byteArrayOf(0x82.toByte(), 0x49.toByte())
    }

    /**
     * 创建原始 Scheduler Action Get PDU 数据
     * OpCode: 0x8248 (2 字节) + Index (1 字节)
     */
    fun createSchedulerActionGetPdu(index: Int): ByteArray {
        return byteArrayOf(
            0x82.toByte(), 0x48.toByte(),  // OpCode
            (index and 0x0F).toByte()      // Index
        )
    }
}

/**
 * Scheduler Action 数据类
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
