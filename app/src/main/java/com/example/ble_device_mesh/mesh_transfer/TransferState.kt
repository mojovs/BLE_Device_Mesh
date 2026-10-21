package com.example.ble_device_mesh.mesh_transfer

enum class TransferState {
    IDLE,             // 空闲
    SEARCHING,        // 搜索附近设备
    DEVICE_FOUND,     // 发现设备，等待确认
    PAIRING,          // 配对验证码确认中
    CONNECTING,       // 建立 WiFi Direct 连接
    CONNECTED,        // 连接就绪
    TRANSFERRING,     // 传输中
    SUCCESS,          // 成功
    FAILED,           // 失败
    CANCEL            // 用户取消
}
