# BLE GAP 和 GATT 详解

## 概念对比

| 特性 | GAP (Generic Access Profile) | GATT (Generic Attribute Profile) |
|------|------------------------------|----------------------------------|
| **层次** | 连接层 | 应用层 |
| **作用** | 设备发现、连接建立 | 数据交换 |
| **类比** | 打电话建立连接 | 通话内容和交流方式 |
| **协议基础** | 基于 Link Layer | 基于 ATT (Attribute Protocol) |

## GAP (Generic Access Profile)

### 核心功能

1. **设备角色定义**
   - **Central（中心设备）**：主动扫描和发起连接（如手机）
   - **Peripheral（外围设备）**：被动广播和接受连接（如 BLE 灯）

2. **广播和扫描**
   - 外围设备发送广播包（Advertisement）
   - 中心设备扫描发现设备

3. **连接管理**
   - 建立 BLE 连接
   - 协商连接参数（连接间隔、超时等）
   - 断开连接

### Android 端代码体现

#### 1. 扫描设备（GAP 层）

**文件**: `BleScannerManager.kt`

```kotlin
// GAP 层：使用 BluetoothLeScanner 扫描设备
private val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

fun startScan(listener: ScanListener) {
    // 设置扫描过滤器，只扫描 Mesh 设备
    val filters = listOf(
        ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(MESH_PROXY_UUID)))
            .build(),
        ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(MESH_PROVISIONING_UUID)))
            .build()
    )
    
    // 设置扫描参数
    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()
    
    // 开始扫描（GAP 层操作）
    bluetoothLeScanner?.startScan(filters, settings, scanCallback)
}
```

**关键点**：
- `startScan()` 是 GAP 层操作
- 扫描广播包中的 Service UUID
- 发现设备后触发 `onScanResult` 回调

#### 2. 连接设备（GAP 层）

**文件**: `BleConnectionManager.kt`

```kotlin
fun connect(device: BluetoothDevice, listener: ConnectionListener) {
    // GAP 层：建立 BLE 连接
    bluetoothGatt = device.connectGatt(
        context, 
        false,  // autoConnect = false
        gattCallback, 
        BluetoothDevice.TRANSPORT_LE  // 使用低功耗蓝牙传输
    )
}

// GAP 层：连接状态变化回调
override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
    when (newState) {
        BluetoothProfile.STATE_CONNECTED -> {
            // 连接成功，开始发现服务（进入 GATT 层）
            gatt.discoverServices()
        }
        BluetoothProfile.STATE_DISCONNECTED -> {
            // 连接断开
            listener?.onDisconnected()
        }
    }
}
```

**关键点**：
- `connectGatt()` 是 GAP 层操作
- 连接成功后才能进行 GATT 层操作
- `onConnectionStateChange` 是 GAP 层回调

### 固件端代码体现

**文件**: `APP/app.c`

```c
// GAP 层：启用扫描（接收配网邀请）
bt_mesh_scan_enable();

// GAP 层：启用广播（发送未配网设备广播）
bt_mesh_beacon_enable();

// GAP 层：启用配网广播
#if(CONFIG_BLE_MESH_PB_GATT)
    bt_mesh_proxy_prov_enable();
#endif
```

**关键点**：
- `bt_mesh_scan_enable()` - 扫描 Mesh 网络中的其他设备
- `bt_mesh_beacon_enable()` - 广播设备状态（未配网/已配网）
- 这些都是 GAP 层操作，负责设备发现和连接建立

---

## GATT (Generic Attribute Profile)

### 核心概念

1. **数据结构层次**
   ```
   Service (服务)
   └── Characteristic (特征)
       └── Descriptor (描述符)
   ```

2. **数据操作**
   - **Read（读）**：读取特征值
   - **Write（写）**：写入特征值
   - **Notify（通知）**：服务端主动推送数据（无需确认）
   - **Indicate（指示）**：服务端主动推送数据（需要确认）

3. **角色**
   - **GATT Server（服务端）**：提供数据的设备（如 BLE 灯）
   - **GATT Client（客户端）**：访问数据的设备（如手机）

### Android 端代码体现

#### 1. 发现服务（GATT 层）

**文件**: `BleConnectionManager.kt`

```kotlin
override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
    if (status == BluetoothGatt.GATT_SUCCESS) {
        // GATT 层：获取 Mesh Provisioning Service
        val provisioningService = gatt.getService(MESH_PROVISIONING_SERVICE_UUID)
        if (provisioningService != null) {
            // GATT 层：获取特征（Characteristic）
            val dataInChar = provisioningService.getCharacteristic(MESH_PROV_DATA_IN_UUID)
            val dataOutChar = provisioningService.getCharacteristic(MESH_PROV_DATA_OUT_UUID)
            
            // GATT 层：启用通知（Notify）
            gatt.setCharacteristicNotification(dataOutChar, true)
        }
        
        // GATT 层：获取 Mesh Proxy Service
        val proxyService = gatt.getService(MESH_PROXY_SERVICE_UUID)
        if (proxyService != null) {
            val dataInChar = proxyService.getCharacteristic(MESH_PROXY_DATA_IN_UUID)
            val dataOutChar = proxyService.getCharacteristic(MESH_PROXY_DATA_OUT_UUID)
            
            gatt.setCharacteristicNotification(dataOutChar, true)
        }
    }
}
```

**关键点**：
- `getService()` - 获取 GATT 服务
- `getCharacteristic()` - 获取 GATT 特征
- `setCharacteristicNotification()` - 启用通知

#### 2. 写入数据（GATT 层）

**文件**: `BleConnectionManager.kt`

```kotlin
fun sendData(data: ByteArray): Boolean {
    val characteristic = proxyDataInCharacteristic ?: return false
    
    // GATT 层：设置特征值
    characteristic.value = data
    
    // GATT 层：写入特征（Write）
    val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val result = gatt.writeCharacteristic(
            characteristic,
            data,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        )
        result == BluetoothGatt.GATT_SUCCESS
    } else {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        gatt.writeCharacteristic(characteristic)
    }
    
    return success
}
```

**关键点**：
- `writeCharacteristic()` - GATT 层写入操作
- `WRITE_TYPE_NO_RESPONSE` - 写入类型（不需要响应）

#### 3. 接收数据（GATT 层）

**文件**: `BleConnectionManager.kt`

```kotlin
override fun onCharacteristicChanged(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray
) {
    // GATT 层：接收到通知（Notify）数据
    when (characteristic.uuid) {
        MESH_PROXY_DATA_OUT_UUID -> {
            // 接收到 Mesh Proxy 数据
            listener?.onDataReceived(value)
        }
        MESH_PROV_DATA_OUT_UUID -> {
            // 接收到配网数据
            listener?.onDataReceived(value)
        }
    }
}
```

**关键点**：
- `onCharacteristicChanged` - GATT 层通知回调
- 服务端主动推送数据到客户端

### 固件端代码体现

**文件**: `APP/app.c`

```c
// GATT 层：注册 GATT 通知回调
gatts_notify_register(bt_mesh_gatts_notify);

// GATT 层：注册 Proxy GATT 服务启用函数
proxy_gatt_enable_register(bt_mesh_proxy_gatt_enable);

// GATT 层：初始化 Proxy 服务
bt_mesh_proxy_init();

// GATT 层：初始化连接广播（用于 GATT 连接）
#if((CONFIG_BLE_MESH_PB_GATT) || (CONFIG_BLE_MESH_PROXY) || (CONFIG_BLE_MESH_OTA))
    bt_mesh_conn_adv_init();
#endif
```

**关键点**：
- `gatts_notify_register()` - 注册 GATT Server 通知回调
- `bt_mesh_proxy_init()` - 初始化 Proxy GATT 服务
- 这些是 GATT 层操作，负责数据交换

---

## Mesh 配网流程中的 GAP 和 GATT

### 完整流程

```
1. GAP 层：手机扫描未配网设备
   └── BleScannerManager.startScan()
   └── 固件：bt_mesh_beacon_enable() 发送广播

2. GAP 层：手机连接到设备
   └── BleConnectionManager.connect()
   └── device.connectGatt()

3. GATT 层：发现服务
   └── gatt.discoverServices()
   └── 固件：提供 Mesh Provisioning Service (UUID: 0x1827)

4. GATT 层：配网数据交换
   └── 手机写入：Mesh Provisioning Data In (UUID: 0x2ADB)
   └── 设备通知：Mesh Provisioning Data Out (UUID: 0x2ADC)
   └── 固件：prov_complete() 回调

5. GAP 层：断开连接（可选）
   └── gatt.disconnect()

6. GATT 层：启用 Proxy GATT 服务
   └── 固件：cfg_srv.gatt_proxy = BLE_MESH_GATT_PROXY_ENABLED
   └── 协议栈自动启用 Mesh Proxy Service (UUID: 0x1828)

7. GAP 层：手机重新连接（通过 Proxy）
   └── 扫描 Mesh Proxy Service
   └── 连接到已配网设备

8. GATT 层：Mesh 消息交换
   └── 手机写入：Mesh Proxy Data In (UUID: 0x2ADD)
   └── 设备通知：Mesh Proxy Data Out (UUID: 0x2ADE)
```

---

## 关键 UUID 总结

### GAP 层（广播中的 Service UUID）

| Service | UUID | 说明 |
|---------|------|------|
| Mesh Provisioning Service | 0x1827 | 未配网设备广播此 UUID |
| Mesh Proxy Service | 0x1828 | 已配网设备广播此 UUID |

### GATT 层（特征 UUID）

| Characteristic | UUID | 方向 | 说明 |
|----------------|------|------|------|
| Mesh Provisioning Data In | 0x2ADB | 手机 → 设备 | 配网数据写入 |
| Mesh Provisioning Data Out | 0x2ADC | 设备 → 手机 | 配网数据通知 |
| Mesh Proxy Data In | 0x2ADD | 手机 → 设备 | Mesh 消息写入 |
| Mesh Proxy Data Out | 0x2ADE | 设备 → 手机 | Mesh 消息通知 |

---

## 为什么配网卡住的问题与 GATT 有关

### 问题分析

1. **配网完成回调在 Mesh 协议栈上下文中执行**
   ```c
   static void prov_complete(...) {
       // 此时处于 Mesh 协议栈的回调上下文
       bt_mesh_proxy_gatt_enable();  // ❌ 危险！
   }
   ```

2. **`bt_mesh_proxy_gatt_enable()` 是 GATT 层操作**
   - 可能触发 GATT 服务注册
   - 可能触发 Flash 写入（保存 Proxy 状态）
   - Flash 写入会禁用中断 20-50ms

3. **BLE 协议栈需要及时响应**
   - 连接间隔通常是 7.5ms - 4s
   - 如果中断被禁用超过连接间隔，会导致连接超时
   - 配网流程卡住或设备死机

### 解决方案

**方案 1：移除调用（推荐）**
```c
static void prov_complete(...) {
    // 不调用 bt_mesh_proxy_gatt_enable()
    // 协议栈会根据 cfg_srv.gatt_proxy = BLE_MESH_GATT_PROXY_ENABLED 自动启用
}
```

**方案 2：延迟任务（备选）**
```c
static void prov_complete(...) {
    // 使用 TMOS 延迟任务异步执行
    tmos_start_task(App_TaskID, APP_PROXY_ENABLE_EVT, TMOS_MS_2_TICKS(100));
}
```

---

## 总结

| 层次 | 职责 | Android API | 固件 API |
|------|------|-------------|----------|
| **GAP** | 设备发现、连接建立 | `startScan()`, `connectGatt()` | `bt_mesh_scan_enable()`, `bt_mesh_beacon_enable()` |
| **GATT** | 数据交换 | `writeCharacteristic()`, `setCharacteristicNotification()` | `gatts_notify_register()`, `bt_mesh_proxy_init()` |

**关键原则**：
- GAP 负责"建立连接"
- GATT 负责"交换数据"
- GATT 操作必须在连接建立后进行
- 避免在 Mesh 回调中直接调用可能触发 Flash 的 GATT 操作
