package com.example.ble_device_mesh.mesh_transfer

import com.google.gson.JsonParser

/**
 * 传输协议封装：在原始 Mesh 配置 JSON 之外增加传输层元信息。
 * 结构：
 * {
 *   "type": "mesh_config",
 *   "version": "1.0",
 *   "sender": { "device_name": "xxx", "app_version": "1.0.0" },
 *   "timestamp": 123456789,
 *   "data": { 原始 mesh 配置 JSON }
 * }
 */
object JsonTransferProtocol {

    const val PROTOCOL_VERSION = "1.0"

    class TransferPayload(
        val meshJson: String,
        val senderName: String,
        val appVersion: String
    )

    class TransferException(message: String) : Exception(message)

    fun wrap(meshJson: String, senderName: String, appVersion: String): String {
        val root = com.google.gson.JsonObject()
        root.addProperty("type", "mesh_config")
        root.addProperty("version", PROTOCOL_VERSION)

        val sender = com.google.gson.JsonObject()
        sender.addProperty("device_name", senderName)
        sender.addProperty("app_version", appVersion)
        root.add("sender", sender)

        root.addProperty("timestamp", System.currentTimeMillis())
        root.add("data", JsonParser.parseString(meshJson))
        return root.toString()
    }

    fun unwrap(envelope: String): TransferPayload {
        val root = try {
            JsonParser.parseString(envelope).asJsonObject
        } catch (e: Exception) {
            throw TransferException("配置文件损坏，无法导入")
        }

        if (root.get("type")?.asString != "mesh_config") {
            throw TransferException("配置文件损坏，无法导入")
        }
        val version = root.get("version")?.asString ?: ""
        if (version != PROTOCOL_VERSION) {
            throw TransferException("双方App版本不一致，请升级")
        }
        val senderName = root.getAsJsonObject("sender")?.get("device_name")?.asString ?: "未知设备"
        val appVersion = root.getAsJsonObject("sender")?.get("app_version")?.asString ?: ""
        val data = root.get("data") ?: throw TransferException("配置文件损坏，无法导入")
        return TransferPayload(data.toString(), senderName, appVersion)
    }
}
