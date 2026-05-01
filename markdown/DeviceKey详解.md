# BLE Mesh DeviceKey 详解

## DeviceKey 是什么？

**DeviceKey** 是 BLE Mesh 配网过程中**自动生成**的设备专属密钥，用于加密 **Configuration Messages**（配置消息）。

### 密钥层次结构

```
BLE Mesh 密钥体系:
├── NetKey (网络密钥)
│   └── 用于加密网络层消息，所有设备共享
├── AppKey (应用密钥)
│   └── 用于加密应用层消息，绑定到特定模型
└── DeviceKey (设备密钥)
    └── 用于加密配置消息，每个设备独有
```

---

## DeviceKey 的生成时机

### 配网流程中的 DeviceKey

```
配网流程:
1. Provisioner 发现未配网设备
2. 建立配网链路 (PB-GATT 或 PB-ADV)
3. 交换公钥 (ECDH)
4. 认证 (OOB 或 No OOB)
5. ✅ Provisioner 生成 DeviceKey (随机生成 128-bit)
6. Provisioner 将 DeviceKey 加密后发送给设备
7. 设备保存 DeviceKey 到 Flash
8. 配网完成
```

**关键点**：
- DeviceKey 由 **Provisioner（配网者）生成**
- 每次配网都会生成**新的随机 DeviceKey**
- 设备和 Provisioner 都保存这个 DeviceKey

---

## 为什么每次配网 DeviceKey 都不一样？

### nRF Mesh app 的行为

```json
// 第一次配网设备 A
{
  "UUID": "13B28AF5-17C8-0000-0000-000000000000",
  "deviceKey": "E577E2C365A3788A9875729831AF5402",  // 随机生成
  "unicastAddress": "0011"
}

// 重置设备 A 后再次配网
{
  "UUID": "13B28AF5-17C8-0000-0000-000000000000",  // UUID 相同
  "deviceKey": "A1B2C3D4E5F6789012345678ABCDEF01",  // DeviceKey 不同！
  "unicastAddress": "0012"  // 地址也可能不同
}
```

**原因**：
1. DeviceKey 是**随机生成**的，不是基于 UUID 派生的
2. 每次配网都是**全新的配网过程**
3. 安全考虑：防止 DeviceKey 泄露后被重复使用

---

## 你的 app 有 DeviceKey 生成步骤吗？

### 答案：有，但是由 nRF Mesh 库自动处理

让我们看看你的代码：

```kotlin
// MeshViewModel.kt
MeshState.meshManagerApi.startProvisioning(meshNode)
```

**这一行代码内部会：**

1. **nRF Mesh 库自动生成 DeviceKey**
   ```kotlin
   // nRF Mesh 库内部（你看不到的代码）
   val deviceKey = SecureRandom().generateSeed(16)  // 生成 128-bit 随机密钥
   ```

2. **通过配网流程发送给设备**
   ```
   Provisioner → [加密的 DeviceKey] → 设备
   ```

3. **保存到数据库**
   ```kotlin
   // nRF Mesh 库自动保存到 mesh_network_database.db
   node.deviceKey = deviceKey
   meshDatabase.insertNode(node)
   ```

### 你不需要手动处理 DeviceKey

**nRF Mesh 库已经帮你做了所有事情**：
- ✅ 生成 DeviceKey
- ✅ 发送给设备
- ✅ 保存到数据库
- ✅ 用于后续配置消息加密

---

## DeviceKey 的用途

### 1. 配置消息加密

DeviceKey 用于加密 **Configuration Messages**，例如：

```kotlin
// 添加 AppKey 到设备
val configAppKeyAdd = ConfigAppKeyAdd(netKey, appKey)
meshManagerApi.createMeshPdu(deviceAddress, configAppKeyAdd)
// ↑ 这个消息会用 DeviceKey 加密
```

**使用 DeviceKey 加密的消息**：
- `ConfigAppKeyAdd` - 添加 AppKey
- `ConfigModelAppBind` - 绑定模型到 AppKey
- `ConfigCompositionDataGet` - 获取设备组成数据
- `ConfigModelSubscriptionAdd` - 添加订阅地址
- 所有 `Config*` 开头的消息

### 2. 与 AppKey 的区别

```
DeviceKey:
├── 用途: 配置消息（Config Messages）
├── 范围: 单个设备专属
├── 生成: 配网时随机生成
└── 示例: ConfigAppKeyAdd, ConfigModelAppBind

AppKey:
├── 用途: 应用消息（Application Messages）
├── 范围: 多个设备共享（绑定到模型）
├── 生成: 创建网络时生成或手动添加
└── 示例: GenericOnOffSet, LightLightnessSet
```

**简单理解**：
- **DeviceKey** = 设备的"管理员密码"，用于配置设备
- **AppKey** = 应用的"访问密码"，用于控制设备功能

---

## 为什么你的 app 控制不了 nRF Mesh 配网的设备？

### 问题分析

```
nRF Mesh app 配网设备:
├── DeviceKey: AAAA (nRF Mesh 生成)
├── AppKey: BBBB (nRF Mesh 的 AppKey)
└── 模型绑定: GenericOnOffServer → AppKey BBBB

你的 app (小米9.json):
├── DeviceKey: 未知（不在你的数据库中）
├── AppKey: CCCC (小米9.json 的 AppKey)
└── 设备信息: 不存在
```

### 发送控制命令时

```kotlin
// 你的 app 发送亮度控制
val message = GenericLevelSetUnacknowledged(level, tid)
meshManagerApi.createMeshPdu(deviceAddress, message)
```

**问题出在哪里？**

1. **设备不在你的数据库中**
   - 你的 app 不知道这个设备的存在
   - 没有 DeviceKey（虽然控制消息不需要 DeviceKey）

2. **AppKey 不匹配**
   - 你的 app 用 AppKey CCCC 加密消息
   - 设备用 AppKey BBBB 解密消息
   - **解密失败** → 设备忽略消息

3. **NetKey 可能也不匹配**
   - 如果 NetKey 不同，消息在网络层就被丢弃了

---

## 解决方案

### 方案 1：导入 nRF Mesh 配置（推荐）

从 nRF Mesh app 导出配置，包含：
- ✅ NetKey
- ✅ AppKey
- ✅ 所有设备的 DeviceKey
- ✅ 设备地址和绑定信息

```bash
# 导出 nRF Mesh 配置
nRF Mesh app → Export network → mesh_export.json

# 导入到你的 app
cp mesh_export.json 小米9.json
./gradlew installDebug
```

### 方案 2：在你的 app 中重新配网

```kotlin
// 你的 app 配网设备
viewModel.provisionDevice(unprovisionedNode)
// ↑ nRF Mesh 库会自动生成新的 DeviceKey
```

配网完成后：
- ✅ DeviceKey 保存在你的数据库中
- ✅ 使用你的 AppKey 绑定模型
- ✅ 可以正常控制设备

---

## 常见问题

### Q1: 我可以手动指定 DeviceKey 吗？

**A**: 不建议。DeviceKey 应该由 Provisioner 随机生成，确保安全性。nRF Mesh 库不提供手动设置 DeviceKey 的接口。

### Q2: 如果我知道设备的 DeviceKey，可以手动添加到数据库吗？

**A**: 理论上可以，但需要：
1. 直接操作 SQLite 数据库
2. 或者通过反射修改 nRF Mesh 库的内部对象
3. 不推荐，容易出错

### Q3: DeviceKey 丢失了怎么办？

**A**: 只能重新配网：
1. 重置设备（清除配网信息）
2. 重新配网，生成新的 DeviceKey
3. 旧的 DeviceKey 无法恢复

### Q4: 为什么控制消息不需要 DeviceKey？

**A**: 
- **配置消息**（Config*）用 DeviceKey 加密
- **应用消息**（Generic*, Light*, Sensor*）用 AppKey 加密
- 控制灯光是应用消息，所以用 AppKey

### Q5: 我的 app 中有 `createVirtualNode` 创建虚拟节点，这个 DeviceKey 有用吗？

**A**: 
```kotlin
val deviceKey = ByteArray(16) { 0xFF.toByte() }  // 虚拟 key
```

这是**无效的 DeviceKey**，只是为了创建虚拟节点占位用的。真实设备的 DeviceKey 必须是配网时生成的。

---

## 调试技巧

### 查看设备的 DeviceKey

```bash
# 从 nRF Mesh 导出的 JSON 中查看
cat mesh_export.json | jq '.nodes[] | {uuid: .UUID, deviceKey: .deviceKey, address: .unicastAddress}'
```

输出：
```json
{
  "uuid": "13B28AF5-17C8-0000-0000-000000000000",
  "deviceKey": "E577E2C365A3788A9875729831AF5402",
  "address": "0011"
}
```

### 验证 AppKey 是否匹配

```kotlin
// 在你的 app 中打印 AppKey
val appKey = network.appKeys.firstOrNull()
Log.d("MeshApp", "AppKey: ${appKey?.key?.joinToString("") { "%02X".format(it) }}")
```

对比 nRF Mesh 导出的 JSON：
```json
{
  "appKeys": [
    {
      "key": "E644AEE801985C7BEDF296E57353B36F",
      "index": 0
    }
  ]
}
```

如果不匹配 → 控制消息会被设备忽略

---

## 总结

### DeviceKey 的关键点

1. **自动生成**：nRF Mesh 库在配网时自动生成
2. **每次不同**：每次配网都生成新的随机 DeviceKey
3. **设备专属**：每个设备有唯一的 DeviceKey
4. **用于配置**：只用于加密配置消息，不用于控制消息
5. **无需手动处理**：nRF Mesh 库自动管理

### 你的 app 控制不了 nRF Mesh 配网设备的原因

**不是 DeviceKey 的问题**，而是：
- ❌ NetKey 不匹配
- ❌ AppKey 不匹配
- ❌ 设备信息不在你的数据库中

### 解决方案

**最简单**：导入 nRF Mesh 的配置文件到你的 app

**最彻底**：在你的 app 中重新配网所有设备
