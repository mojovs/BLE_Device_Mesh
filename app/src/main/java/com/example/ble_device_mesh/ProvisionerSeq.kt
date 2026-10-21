package com.example.ble_device_mesh

import android.content.Context
import android.util.Log
import no.nordicsemi.android.mesh.MeshNetwork

/**
 * 保证 Provisioner 的序列号（SEQ）始终向前推进。
 *
 * 背景：Mesh 设备按「源地址 + SEQ」做重放过滤（RPL），同一源地址的新序号必须大于设备已见过的序号。
 * 导入备份 / 清空数据 / 切换 Provisioner 地址后，本地 SEQ 会从很小的值重新开始，
 * 而设备端 RPL 里记着老的高序号，于是所有消息被当作重放包丢弃 —— 表现为「所有设备都无响应、
 * 时间同步全部超时」，但 BLE 连接、网络密钥都正常。
 *
 * 这里把 SEQ 一次性抬到安全基线并持久化，之后正常自增；已经高于基线时不做任何改动。
 */
internal object ProvisionerSeq {

    private const val PREFS = "mesh_config"
    private const val KEY_FLOOR = "provisioner_seq_floor"

    /** 安全基线：远高于历史上正常使用可能到达的值，同时给后续自增留足空间（SEQ 上限 0xFFFFFF） */
    private const val BASELINE = 10_000_000
    private const val MAX_SEQ = 0xFFFFFF

    fun ensureForward(context: Context, network: MeshNetwork?, tag: String = "MeshApp") {
        if (network == null) return
        try {
            val provisioner = network.selectedProvisioner ?: return
            val node = network.getNode(provisioner.provisionerUuid) ?: return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val floor = prefs.getInt(KEY_FLOOR, 0)
            val target = maxOf(node.sequenceNumber, floor, BASELINE)
            if (target > MAX_SEQ) {
                Log.w(tag, "Provisioner SEQ 无法抬升（已接近上限）: ${node.sequenceNumber}")
                return
            }
            if (node.sequenceNumber < target) {
                Log.w(tag, "Provisioner SEQ 抬升: ${node.sequenceNumber} -> $target（绕开设备端重放过滤）")
                node.setSequenceNumber(target)
                prefs.edit().putInt(KEY_FLOOR, target).apply()
            }
        } catch (e: Exception) {
            Log.w(tag, "调整 Provisioner SEQ 失败: ${e.message}")
        }
    }
}
