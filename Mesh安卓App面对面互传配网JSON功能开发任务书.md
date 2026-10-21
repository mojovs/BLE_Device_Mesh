# Mesh 安卓 App 新增"面对面互传"功能开发任务书

## 1. 项目背景

当前 Mesh 安卓 App 已具备设备配网功能，用户可以在 App 内生成并保存当前 Mesh 网络配置文件（JSON），并通过「设置」页手动导入/导出。

现在需要在设置页新增一个"面对面互传"功能，实现两个手机之间面对面快速传输配网配置 JSON 文件，省去文件导出/导入的手动步骤。

目标体验类似手机自带的"面对面互传"功能：

- 手机 A：选择发送当前 Mesh 配网配置
- 手机 B：打开接收功能
- 两台手机建立近距离连接
- A 将 JSON 配置文件发送给 B
- B 接收后自动导入 Mesh 配网配置

## 2. 功能入口

### 页面位置（已确定）

```
我的（底部导航）
 └── 设置（SettingsActivity）
      └── Mesh 网络配置 卡片
           ├── 导入 Mesh 配置 (Json)
           ├── 导出 Mesh 配置 (Json)
           └── 【新增】面对面互传
```

代码位置：

- 页面：`app/src/main/java/com/example/ble_device_mesh/SettingsActivity.kt`
- 布局：`app/src/main/res/layout/activity_settings.xml`（在 `btnExportConfig` 下方、`tvConfigStatus` 上方新增按钮 `btnMeshTransfer`）
- 入口语义与已有导入/导出按钮一致，归属"配网管理"范畴，改动最小

入口 UI：

名称：

```
面对面互传
```

图标：

建议：
- 两部手机
- 双向箭头
- WiFi / Bluetooth 标识

点击进入互传页面（TransferActivity）。

## 3. 功能流程设计

## 3.1 发送端流程

用户手机 A：

进入：

```
我的 -> 设置 -> 面对面互传 -> 发送配置
```

流程：

1. 查询当前 App 保存的 Mesh 配网 JSON

   直接复用 `MeshViewModel.exportMeshNetworkWithDevices()`（`MeshViewModel.kt:2264`），返回合并格式 JSON：

   ```json
   {
       "version": 1,
       "meshNetwork": {
           // nRF Mesh 库导出的完整网络 JSON（含 networkKey、appKey 等密钥）
       },
       "devices": [
           {
               "name": "light01",
               "address": 4,
               "type": "LIGHT",
               "brightness": 50,
               "groupAddress": null,
               "bluetoothMac": "xx:xx:xx:xx:xx:xx"
           }
       ]
   }
   ```

2. 显示确认页面：

   内容：

   ```
   即将发送 Mesh 配置

   设备数量:
   10

   网络:
   home_mesh

   [开始发送]
   ```

3. 点击开始发送：

   开启附近设备发现（BLE 扫描 + WiFi Direct 发现）。

   显示：

   ```
   正在搜索附近设备...
   ```

4. 发现接收手机：

   显示：

   ```
   发现设备：

   JoJo手机

   [发送]
   ```

5. 用户确认发送，进入配对确认：

   双方显示 6 位验证码：

   ```
   验证码：728391
   请确认对方手机显示相同验证码

   [取消] [确认一致]
   ```

   用户确认一致后建立连接。

6. 开始传输：

   显示：

   ```
   正在发送...

   45%

   预计剩余:
   5秒
   ```

7. 完成：

   ```
   发送成功
   ```

## 3.2 接收端流程

手机 B：

进入：

```
我的 -> 设置 -> 面对面互传 -> 接收配置
```

显示：

```
等待附近设备发送配置...

请保持两个手机靠近
```

收到请求：

弹窗：

```
收到 Mesh 配置请求

来自:
JoJo手机

包含设备:
10个


是否接收？

[拒绝]
[接收]
```

点击接收，进入配对确认：

```
验证码：728391
请确认对方手机显示相同验证码

[取消] [确认一致]
```

用户确认一致后开始下载。

完成：

显示：

```
接收成功


是否立即导入配置？

[取消]
[导入]
```

点击导入：调用 `MeshViewModel.importMeshNetworkWithDevices()`（`MeshViewModel.kt:2293`）自动导入 Mesh 网络 + 设备元数据。

## 4. 通信方案要求（已确定）

**最终选型：WiFi Direct + BLE 发现**

采用 Android 原生近距离通信方案，纯 Android 框架 API（`android.net.wifi.p2p` + `android.bluetooth.le`），**无需新增 Google Play Services 依赖**，不需要服务器。

### 分工

```
BLE（发现 + 配对确认）
 ├── 搜索附近设备（广播自定义 Service UUID）
 ├── 交换连接信息（WiFi Direct 群组 SSID / 密码）
 └── 交换配对验证码、随机盐

WiFi Direct（高速传输）
 ├── 建立点对点群组（GO / Client）
 ├── 建立 TCP 连接
 └── 传输加密后的 JSON 文件
```

### 详细流程

1. **发现阶段（BLE）**
   - 接收端（B）：通过 `BluetoothLeAdvertiser` 广播自定义 Service UUID（如 `MeshTransferService`），同时开启 WiFi Direct `discoverPeers()`。
   - 发送端（A）：通过 `BluetoothLeScanner` 扫描该 Service UUID 发现 B；同时监听 WiFi Direct peer 变化。
   - 发送端点击设备后，双方通过 BLE GATT 建立短连接，交换 WiFi Direct 群组信息与配对码。

2. **配对确认（BLE）**
   - A 生成 6 位验证码，通过 BLE 发给 B 显示；双方用户目视确认一致后，在各自手机点击「确认一致」。
   - 验证码同时作为会话密钥派生种子（见 §6.1）。

3. **建立群组（WiFi Direct）**
   - 发送端 A 创建 WiFi Direct 群组成为 Group Owner（GO），拿到 SSID/密码。
   - A 通过 BLE 将 SSID/密码下发给 B，B 调用 `WifiManager.connect()` 加入群组（或通过 `p2pConnect` 自动加组）。

4. **传输（TCP）**
   - A 在 GO 侧开启 TCP ServerSocket（固定端口），B 连接后 A 发送加密 JSON 数据包。
   - 传输完成后 A 关闭群组，B 断开 WiFi Direct 连接并恢复原 WiFi。

### 优势

- 不需要服务器
- 局域网高速传输，<1MB JSON <5 秒
- 类似面对面快传体验
- 无 GMS 依赖，兼容性可控

### 已否决方案及原因（记录）

| 方案 | 原因 |
| ---- | ---- |
| Nearby Connections API | 需新增 `com.google.android.gms:play-services-nearby` 依赖；项目当前无任何 GMS 依赖（`app/build.gradle.kts`），引入依赖与国内设备无 GMS 场景不符 |
| BLE GATT 直传 | BLE MTU 限制导致 <1MB JSON 传输效率低、耗时长，不符合 <5 秒要求 |

## 5. 数据格式

### 发送文件内容

文件内容保持当前项目格式，**直接复用** `MeshViewModel.exportMeshNetworkWithDevices()` 的合并 JSON（见 §3.1），不新建数据模型、不改变现有 JSON 格式。

### 传输协议封装

在原始 JSON 之外新增传输层封装：

```json
{
    "type": "mesh_config",
    "version": "1.0",

    "sender":{
        "device_name":"xxx",
        "app_version":"1.0.0"
    },

    "timestamp":123456789,

    "data":{
        "// 说明": "原始 mesh 配置 JSON（exportMeshNetworkWithDevices 输出）"
    }
}
```

由 `JsonTransferProtocol.kt` 负责封装/解封装。版本号用于校验双方 App 协议兼容性（§11 版本不兼容提示）。

## 6. 安全要求

因为配置文件包含 Mesh 密钥（networkKey / appKey），必须保护。

### 6.1 传输加密

算法：

```
AES-256-GCM（javax.crypto 内置，Cipher "AES/GCM/NoPadding"）
```

不引入第三方加密库。

密钥派生：

```
6位验证码（用户目视确认）
      +
BLE 交换的 256-bit 随机盐
      ↓
PBKDF2/HKDF 派生 256-bit 会话密钥
```

流程：

发送：

```
JSON
 |
AES-GCM 加密（随机 12-byte IV + 128-bit tag）
 |
Socket 发送
```

接收：

```
Socket
 |
AES-GCM 解密 + 完整性校验（GCM tag）
 |
校验 JSON 封装
```

### 6.2 配对确认

首次连接：

双方显示相同 6 位验证码：

```
728391
```

用户确认一致后开始传输。验证码同时参与会话密钥派生，防止中间人。

### 6.3 不允许后台自动接收

必须：

用户主动点击：

```
接收
```

才允许写入（导入配置）。接收端页面必须在前台保持开启，不允许后台服务静默接收。

## 7. Android 开发要求

### 权限处理

本项目 minSdk 24 / targetSdk 36，按 Android 版本适配动态申请权限。

Android 13+（API 33+）：

```
BLUETOOTH_SCAN
BLUETOOTH_CONNECT
BLUETOOTH_ADVERTISE
NEARBY_WIFI_DEVICES
```

Android 12-（API 31 及以下）：

```
ACCESS_FINE_LOCATION
BLUETOOTH
BLUETOOTH_ADMIN
```

说明：

- 蓝牙相关权限（`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE`）已在 `AndroidManifest.xml` 中声明，互传功能复用现有权限申请逻辑（参照 `ProvisionActivity` / `DeviceDetailActivity` 的扫描前定位弹窗处理）。
- WiFi Direct（`android.net.wifi.p2p`）在 Android 13+ 需要新增 `NEARBY_WIFI_DEVICES` 运行时权限；Android 12- 依赖位置权限。Manifest 需新增：

  ```xml
  <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
      android:usesPermissionFlags="neverForLocation" />
  ```

- 所有权限在进入互传页时统一动态申请，任一必需权限被拒绝则提示并停留在入口页（不静默降级）。

### WiFi Direct 广播注册

在 TransferActivity 注册 `WifiP2pManager` 的 IntentFilter 并处理 `WIFI_P2P_STATE_CHANGED_ACTION` / `WIFI_P2P_PEERS_CHANGED_ACTION` 等广播。

## 8. 项目代码要求

开发前先分析当前项目结构（已完成，结论如下）：

```
app/src/main/java/com/example/ble_device_mesh/
 ├── MainActivity.kt            （底部导航，navSettings -> SettingsActivity）
 ├── SettingsActivity.kt        （导入/导出入口，新增互传入口）
 ├── MeshViewModel.kt           （exportMeshNetworkWithDevices / importMeshNetworkWithDevices）
 ├── BleScannerManager.kt       （BLE 扫描，可复用）
 ├── BleConnectionManager.kt    （BLE 连接，含 MIUI 兼容，可参考）
 ├── data/                      （DeviceRepository、MeshDevice 等本地数据）
 └── res/layout/activity_settings.xml
```

已确认事项：

1. Mesh JSON 导出位置：`MeshViewModel.exportMeshNetworkWithDevices()`（`MeshViewModel.kt:2264`）
2. Mesh JSON 导入位置：`MeshViewModel.importMeshNetworkWithDevices()`（`MeshViewModel.kt:2293`，兼容合并格式与纯 nRF Mesh 旧格式）
3. 配网数据模型：`data/MeshDevice.kt`、`data/DeviceRepository.kt`，**互传功能不新建数据模型**
4. 设置页入口代码：`SettingsActivity.kt` + `activity_settings.xml`

不要重复创建数据模型、不要改动已有导入/导出方法签名。

## 9. 推荐代码结构

新增模块（Kotlin，包名 `com.example.ble_device_mesh.mesh_transfer`）：

```
mesh_transfer/
 ├── TransferActivity.kt        （互传页面容器：发送/接收两个 Tab）
 ├── SendFragment.kt            （发送配置流程）
 ├── ReceiveFragment.kt         （接收配置流程）
 ├── TransferState.kt           （枚举：SEARCHING/DEVICE_FOUND/...）
 ├── WifiDirectManager.kt       （WiFi Direct 建组/加组/TCP 传输）
 ├── BleDiscoveryManager.kt     （BLE 发现 + 配对码交换，复用现有扫描能力）
 ├── CryptoManager.kt           （AES-GCM 加解密 + 密钥派生）
 ├── JsonTransferProtocol.kt    （传输协议封装/解封装 + 版本校验）
 └── TransferCallback.kt        （传输进度/状态回调接口）
```

## 10. 状态管理

定义状态（Kotlin 枚举）：

```kotlin
enum class TransferState {
    SEARCHING,       // 搜索附近设备
    DEVICE_FOUND,    // 发现设备，等待确认
    PAIRING,         // 配对验证码确认中
    CONNECTING,      // 建立 WiFi Direct 连接
    CONNECTED,       // 连接就绪
    TRANSFERRING,    // 传输中
    SUCCESS,         // 成功
    FAILED,          // 失败
    CANCEL           // 用户取消
}
```

UI 根据状态刷新对应界面；发送/接收两侧状态机一致，通过传输协议同步推进。

## 11. 异常处理

必须处理：

### 发送失败

例如：
- 对方退出
- WiFi Direct 连接断开
- 权限拒绝
- 传输超时

提示：

```
发送失败，请重新尝试
```

提供重试按钮。

### JSON 错误

解密后 JSON 校验失败（GCM tag 不匹配 / 结构不合法）：

提示：

```
配置文件损坏，无法导入
```

### 版本不兼容

传输协议 `version` 不一致：

提示：

```
双方App版本不一致，请升级
```

### WiFi 冲突

WiFi Direct 连接需要短暂占用 WLAN，期间可能断开原有 WiFi；传输结束后必须恢复原 WiFi 连接。需在流程说明中提示用户。

## 12. 验收标准

### 基础功能

- [ ] 设置页 Mesh 配置卡片出现「面对面互传」入口
- [ ] 可以选择发送配置
- [ ] 可以选择接收配置
- [ ] 两台 Android 手机可以发现对方
- [ ] 6 位配对验证码双方显示一致且可确认
- [ ] JSON 可以完整传输（导入后与发送端设备数据一致）
- [ ] 接收后可以一键导入（复用 importMeshNetworkWithDevices）
- [ ] 传输结束后双方 WiFi 恢复正常

### 性能

测试：

JSON 大小：

```
<1MB
```

要求：

传输时间：

```
<5秒
```

（自 BLE 配对确认完成、WiFi Direct 连接建立后计时）

### 安全

- [ ] 传输全程 AES-256-GCM 加密
- [ ] 有确认验证码（且参与密钥派生）
- [ ] 未授权不能导入（必须手动点击「接收」「导入」）
- [ ] 验证码不匹配时拒绝连接

### 兼容性

- [ ] Android 13+ 真机验证（NEARBY_WIFI_DEVICES 权限流程）
- [ ] Android 12 及以下真机验证（位置权限流程）
- [ ] 双端 App 版本不一致时给出升级提示

## 13. OpenCode 执行要求

执行步骤：

1. 先扫描 Android 工程结构（已有结论见 §8）
2. 找到当前 Mesh 配置 JSON 存储方式（`MeshViewModel.exportMeshNetworkWithDevices` / `importMeshNetworkWithDevices`）
3. 输出修改计划
4. 等待确认后修改代码
5. 实现完整功能
6. 编译检查（`.\gradlew.bat :app:assembleDebug`）
7. 修复错误
8. 输出修改文件列表

禁止：

- 大范围重构已有 Mesh 代码
- 修改已有配网协议
- 改变现有 JSON 格式（导出/导入格式保持不变）
- 改动 `MeshViewModel.exportMeshNetworkWithDevices` / `importMeshNetworkWithDevices` 方法签名
- 删除已有功能
- 新增 Google Play Services 依赖

最终输出：

```
修改文件:
xxx.kt
xxx.xml
xxx.gradle（如涉及，优先避免）

新增文件:
xxx.kt

测试结果:
Build success
```
