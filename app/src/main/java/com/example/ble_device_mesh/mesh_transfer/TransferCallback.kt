package com.example.ble_device_mesh.mesh_transfer

interface TransferCallback {
    fun onStateChanged(state: TransferState)
    fun onProgress(percent: Int)
    fun onDeviceFound(name: String)
    fun onPairCode(code: String)
    fun onPeerConfirmed()
    fun onTransferSuccess(payloadJson: String)
    fun onError(message: String)
    fun onLog(message: String)
}
