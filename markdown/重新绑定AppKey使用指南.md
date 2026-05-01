# 重新绑定 AppKey 功能使用指南

## 功能说明

当你在 app 中配网设备后，又导入了 nRF Mesh 的 JSON 配置，会导致设备无法响应控制命令。这是因为：

1. **配网时**：设备保存了 app 当前的 AppKey (假设是 AAAA)
2. **导入 JSON 后**：app 使用 nRF Mesh 的 AppKey (假设是 BBBB)
3. **发送控制命令**：app 用 AppKey BBBB 加密，设备用 AppKey AAAA 解密 → 失败

**重新绑定 AppKey** 功能会将新的 AppKey 添加到设备，并重新绑定所有模型，解决这个问题。

---

## 使用步骤

### 1. 打开设备详情页面

在主界面点击设备，进入设备详情页面。

### 2. 找到"重新绑定 AppKey"按钮

在 **设备信息** 卡片中，找到红色的 **"重新绑定 AppKey"** 按钮。

### 3. 点击按钮

点击按钮后，会弹出确认对话框：

```
重新绑定 AppKey

此操作将重新绑定设备的 AppKey，用于解决导入 JSON 后控制失效的问题。

设备地址: 0x0013

是否继续？

[取消]  [确定]
```

### 4. 确认操作

点击 **"确定"** 开始重新绑定。

### 5. 等待完成

app 会自动执行以下步骤：

1. **添加 AppKey 到设备** (1 秒)
   - 发送 `ConfigAppKeyAdd` 命令
   - 设备保存新的 AppKey

2. **绑定模型到 AppKey** (1 秒)
   - 绑定 Generic Level Model (0x1002)
   - 绑定 Light Lightness Model (0x1100)
   - 绑定 Sensor Model (0x1200)
   - 绑定 Time Model (0x1206)
   - 绑定 Time Setup Model (0x1207)

3. **完成** (2 秒后)
   - 显示 "AppKey 重新绑定完成，请尝试控制设备"

### 6. 测试控制

重新绑定完成后，尝试调节亮度滑块，检查设备是否响应。

---

## 日志输出

### 成功的日志

```
D/MeshApp: === 开始重新绑定 AppKey ===
D/MeshApp:   设备地址: 0x13
D/MeshApp:   设备名称: 灯3
D/MeshApp:   AppKey Index: 0
D/MeshApp:   AppKey: E644AEE801985C7BEDF296E57353B36F
D/MeshApp: 已发送 ConfigAppKeyAdd
D/MeshApp: 开始绑定模型...
D/MeshApp:   绑定模型 0x1002 (Element 0x13)
D/MeshApp:   绑定模型 0x1100 (Element 0x13)
D/MeshApp:   绑定模型 0x1200 (Element 0x13)
D/MeshApp:   绑定模型 0x1206 (Element 0x13)
D/MeshApp:   绑定模型 0x1207 (Element 0x13)
D/MeshApp: 已发送 5 个模型绑定命令
D/MeshApp: === AppKey 重新绑定完成 ===
```

### 失败的日志

```
E/MeshApp: 网络未加载
// 或
E/MeshApp: 未找到设备 0x13
// 或
E/MeshApp: 未找到 AppKey
```

---

## 常见问题

### Q1: 重新绑定后还是无法控制设备？

**可能原因**：
1. **NetKey 也不匹配**：重新绑定只解决 AppKey 问题，如果 NetKey 也不同，仍然无法控制
2. **设备未连接**：确保设备已连接到 Proxy 节点
3. **设备地址错误**：检查设备地址是否正确

**解决方法**：
- 检查 logcat 日志，查看是否有错误信息
- 尝试重新配网设备（最彻底的方法）

### Q2: 需要对每个设备都执行重新绑定吗？

**是的**。每个设备都需要单独重新绑定 AppKey。

### Q3: 重新绑定会影响设备的其他配置吗？

**不会**。重新绑定只是添加新的 AppKey 并重新绑定模型，不会影响：
- 设备地址
- DeviceKey
- 其他已配置的参数

### Q4: 可以在主界面批量重新绑定吗？

**目前不支持**。需要逐个进入设备详情页面操作。

如果需要批量操作，可以添加功能：

```kotlin
// 在 MainActivity 中添加
fun rebindAllDevices() {
    val devices = deviceRepository.getAllDevices()
    devices.forEach { device ->
        viewModel.rebindAppKey(device.address)
        Thread.sleep(5000)  // 等待 5 秒
    }
}
```

### Q5: 什么时候需要使用这个功能？

**使用场景**：
1. 在你的 app 中配网设备后，导入了 nRF Mesh 的 JSON
2. 在 nRF Mesh 中配网设备后，想在你的 app 中控制
3. 更换了 Mesh 网络配置（NetKey 或 AppKey）

**不需要使用的场景**：
1. 正常配网后，没有导入其他配置
2. 设备可以正常响应控制命令

---

## 技术细节

### 发送的 Mesh 消息

#### 1. ConfigAppKeyAdd

```kotlin
ConfigAppKeyAdd(netKey, appKey)
```

**作用**：将 AppKey 添加到设备的 AppKey 列表

**设备端行为**：
- 检查 AppKey 是否已存在
- 如果不存在，添加到列表
- 如果已存在，忽略（不会报错）

#### 2. ConfigModelAppBind

```kotlin
ConfigModelAppBind(elementAddress, modelId, appKeyIndex)
```

**作用**：将模型绑定到 AppKey

**设备端行为**：
- 检查模型是否已绑定该 AppKey
- 如果未绑定，添加绑定
- 如果已绑定，更新绑定（覆盖旧的）

### 绑定的模型列表

| Model ID | 模型名称 | 用途 |
|----------|---------|------|
| 0x1002 | Generic Level Server | 亮度控制 |
| 0x1100 | Light Lightness Server | 灯光亮度 |
| 0x1200 | Sensor Server | 传感器数据 |
| 0x1206 | Time Server | 时间同步 |
| 0x1207 | Time Setup Server | 时间设置 |

### 时序图

```
App                          Device
 |                              |
 |--- ConfigAppKeyAdd --------->|
 |                              | 保存 AppKey
 |<-- Status (Success) ---------|
 |                              |
 | (延迟 1 秒)                   |
 |                              |
 |--- ConfigModelAppBind ------>| (Model 0x1002)
 |                              | 绑定模型
 |<-- Status (Success) ---------|
 |                              |
 |--- ConfigModelAppBind ------>| (Model 0x1100)
 |<-- Status (Success) ---------|
 |                              |
 |--- ConfigModelAppBind ------>| (Model 0x1200)
 |<-- Status (Success) ---------|
 |                              |
 | ... (其他模型)                |
 |                              |
 | (延迟 2 秒)                   |
 |                              |
 | 完成                          |
```

---

## 代码实现

### ViewModel 中的实现

```kotlin
fun rebindAppKey(address: Int) {
    // 1. 检查网络和密钥
    val network = MeshState.meshNetWork ?: return
    val appKey = network.appKeys.firstOrNull() ?: return
    val netKey = network.netKeys.firstOrNull() ?: return
    
    // 2. 添加 AppKey
    MeshState.meshManagerApi.createMeshPdu(
        address,
        ConfigAppKeyAdd(netKey, appKey)
    )
    
    // 3. 延迟后绑定模型
    Handler.postDelayed({
        node.elements?.forEach { element ->
            element.value.meshModels?.forEach { (modelId, _) ->
                if (listOf(0x1002, 0x1100, 0x1200, 0x1206, 0x1207).contains(modelId)) {
                    MeshState.meshManagerApi.createMeshPdu(
                        address,
                        ConfigModelAppBind(element.value.elementAddress, modelId, appKey.keyIndex)
                    )
                }
            }
        }
    }, 1000)
}
```

### UI 中的调用

```kotlin
btnRebindAppKey.setOnClickListener {
    AlertDialog.Builder(this)
        .setTitle("重新绑定 AppKey")
        .setMessage("是否继续？")
        .setPositiveButton("确定") { _, _ ->
            viewModel.rebindAppKey(device.address)
        }
        .setNegativeButton("取消", null)
        .show()
}
```

---

## 总结

**重新绑定 AppKey** 功能是解决导入 JSON 后控制失效问题的快速方法。它通过重新添加 AppKey 并绑定模型，让设备使用新的 AppKey 解密控制消息。

**优点**：
- 无需重新配网
- 操作简单快速
- 不影响其他配置

**缺点**：
- 需要逐个设备操作
- 如果 NetKey 也不匹配，仍然无法解决

**最佳实践**：
- 先导入 JSON，再配网设备（避免 AppKey 不匹配）
- 或者在你的 app 中配网，导出 JSON 给 nRF Mesh 使用
