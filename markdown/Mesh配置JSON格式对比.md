# Mesh 配置 JSON 格式对比分析

## nRF Mesh 标准格式（小米9.json）

### 顶层结构

```json
{
  "$schema": "http://json-schema.org/draft-04/schema#",
  "id": "https://www.bluetooth.com/specifications/specs/mesh-cdb-1-0-1-schema.json#",
  "version": "1.0.1",
  "meshUUID": "7BBCA295-5F49-4C79-9231-3CB340D5DBC2",
  "meshName": "小米9",
  "timestamp": "2026-04-23T20:09:44+08:00",
  "partial": false,
  "netKeys": [...],
  "appKeys": [...],
  "provisioners": [...],
  "nodes": [...],
  "groups": [...],      // 可选
  "scenes": [...]       // 可选
}
```

### 1. 基本信息字段

| 字段 | 必需 | 说明 | 示例 |
|------|------|------|------|
| `$schema` | ✅ | JSON Schema 定义 | `"http://json-schema.org/draft-04/schema#"` |
| `id` | ✅ | Mesh 规范 ID | `"https://www.bluetooth.com/specifications/specs/mesh-cdb-1-0-1-schema.json#"` |
| `version` | ✅ | 配置版本 | `"1.0.1"` |
| `meshUUID` | ✅ | Mesh 网络唯一标识 | `"7BBCA295-5F49-4C79-9231-3CB340D5DBC2"` |
| `meshName` | ✅ | 网络名称 | `"小米9"` |
| `timestamp` | ✅ | 最后更新时间 | `"2026-04-23T20:09:44+08:00"` |
| `partial` | ✅ | 是否部分导出 | `false` |

### 2. netKeys（网络密钥）

```json
"netKeys": [
  {
    "name": "Network Key 1",
    "index": 0,
    "key": "21AC250562E2882B93C3BCCD6B9873CE",  // 32 字符十六进制（128-bit）
    "phase": 0,                                  // Key Refresh 阶段
    "minSecurity": "insecure",                   // 或 "secure"
    "timestamp": "2026-04-23T20:09:44+08:00"
  }
]
```

**关键字段**：
- `key`: 128-bit 密钥，32 字符十六进制
- `phase`: 0=正常, 1=Key Refresh Phase 1, 2=Phase 2
- `minSecurity`: 最低安全级别

### 3. appKeys（应用密钥）

```json
"appKeys": [
  {
    "name": "Application Key 1",
    "index": 0,
    "boundNetKey": 0,                            // 绑定的 NetKey index
    "key": "E644AEE801985C7BEDF296E57353B36F"   // 32 字符十六进制（128-bit）
  }
]
```

**关键字段**：
- `boundNetKey`: 必须绑定到一个 NetKey
- `key`: 128-bit 密钥

### 4. provisioners（配网者）

```json
"provisioners": [
  {
    "provisionerName": "CH Mesh Provisioner",
    "UUID": "00BF3BF1-C603-498A-9B5B-E0E943810438",
    "allocatedUnicastRange": [
      {
        "lowAddress": "0100",    // 4 字符十六进制
        "highAddress": "199A"
      }
    ],
    "allocatedGroupRange": [
      {
        "lowAddress": "C000",
        "highAddress": "CC9A"
      }
    ],
    "allocatedSceneRange": [
      {
        "firstScene": "0001",
        "lastScene": "3333"
      }
    ]
  }
]
```

**关键字段**：
- `allocatedUnicastRange`: 单播地址分配范围（必需）
- `allocatedGroupRange`: 组地址分配范围（必需）
- `allocatedSceneRange`: 场景编号分配范围（可选）

### 5. nodes（设备节点）

#### 5.1 Provisioner 节点

```json
{
  "UUID": "00BF3BF1-C603-498A-9B5B-E0E943810438",
  "name": "CH Mesh Provisioner",
  "deviceKey": "8F5FAB6CD90D9F0DA3D6F6E81A7CCB61",
  "unicastAddress": "0099",
  "security": "insecure",
  "configComplete": false,
  "features": {
    "friend": 2,      // 0=不支持, 1=支持, 2=未知
    "lowPower": 2,
    "proxy": 2,
    "relay": 2
  },
  "defaultTTL": 5,
  "netKeys": [
    {
      "index": 0,
      "updated": false
    }
  ],
  "appKeys": [
    {
      "index": 0,
      "updated": false
    }
  ],
  "elements": [
    {
      "name": "Element: 0x0099",
      "index": 0,
      "location": "0000",
      "models": [
        {
          "modelId": "0001",    // Configuration Server
          "bind": [],
          "subscribe": []
        }
      ]
    }
  ],
  "excluded": false
}
```

#### 5.2 普通设备节点

```json
{
  "UUID": "13B28AF5-17C8-0000-0000-000000000000",
  "name": "灯1",
  "deviceKey": "E577E2C365A3788A9875729831AF5402",
  "unicastAddress": "0011",
  "security": "insecure",
  "configComplete": false,
  "cid": "07D7",        // Company ID
  "pid": "0000",        // Product ID
  "vid": "0000",        // Version ID
  "crpl": "0014",       // Replay Protection List Size
  "features": {
    "friend": 2,
    "lowPower": 2,
    "proxy": 0,         // 0=不支持
    "relay": 0
  },
  "defaultTTL": 3,
  "netKeys": [
    {
      "index": 0,
      "updated": false
    }
  ],
  "appKeys": [
    {
      "index": 0,
      "updated": false
    }
  ],
  "elements": [
    {
      "name": "Element: 0x0011",
      "index": 0,
      "location": "0000",
      "models": [
        {
          "modelId": "0000",    // Configuration Server
          "bind": [],
          "subscribe": []
        },
        {
          "modelId": "0002",    // Health Server
          "bind": [],
          "subscribe": []
        },
        {
          "modelId": "1002",    // Generic Level Server
          "bind": [0],          // 绑定到 AppKey index 0
          "subscribe": []
        },
        {
          "modelId": "1100",    // Light Lightness Server
          "bind": [0],
          "subscribe": []
        },
        {
          "modelId": "1200",    // Sensor Server
          "bind": [0],
          "subscribe": []
        },
        {
          "modelId": "1206",    // Time Server
          "bind": [0],
          "subscribe": []
        },
        {
          "modelId": "1207",    // Time Setup Server
          "bind": [0],
          "subscribe": []
        }
      ]
    }
  ],
  "excluded": false
}
```

**关键字段**：
- `deviceKey`: 配网时生成的设备专属密钥（必需）
- `cid`, `pid`, `vid`, `crpl`: 设备信息（从 Composition Data 获取）
- `elements`: 设备的元素列表
  - `models`: 每个元素包含的模型
    - `bind`: 绑定的 AppKey index 列表
    - `subscribe`: 订阅的组地址列表

### 6. groups（组地址）- 可选

```json
"groups": [
  {
    "name": "All Lights",
    "address": "C001",
    "parentAddress": "0000"
  }
]
```

### 7. scenes（场景）- 可选

```json
"scenes": [
  {
    "name": "Scene 1",
    "number": "0001",
    "addresses": ["0011", "0012"]
  }
]
```

---

## 你的 app 导出功能检查清单

### 必需字段

- [ ] `$schema`
- [ ] `id`
- [ ] `version`
- [ ] `meshUUID`
- [ ] `meshName`
- [ ] `timestamp`
- [ ] `partial`
- [ ] `netKeys` (包含 `name`, `index`, `key`, `phase`, `minSecurity`, `timestamp`)
- [ ] `appKeys` (包含 `name`, `index`, `boundNetKey`, `key`)
- [ ] `provisioners` (包含地址分配范围)
- [ ] `nodes` (包含所有已配网设备)

### 节点必需字段

- [ ] `UUID`
- [ ] `name`
- [ ] `deviceKey` ⚠️ **最关键！**
- [ ] `unicastAddress`
- [ ] `security`
- [ ] `configComplete`
- [ ] `features`
- [ ] `defaultTTL`
- [ ] `netKeys`
- [ ] `appKeys`
- [ ] `elements`
  - [ ] `models`
    - [ ] `modelId`
    - [ ] `bind` (绑定的 AppKey)
    - [ ] `subscribe`

### 设备信息字段（从 Composition Data 获取）

- [ ] `cid` (Company ID)
- [ ] `pid` (Product ID)
- [ ] `vid` (Version ID)
- [ ] `crpl` (Replay Protection List Size)

---

## 常见缺失问题

### 1. deviceKey 缺失或错误

**问题**：
```json
{
  "UUID": "...",
  "deviceKey": "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",  // ❌ 虚拟 key
  "unicastAddress": "0011"
}
```

**影响**：
- 无法发送配置消息（ConfigAppKeyAdd, ConfigModelAppBind 等）
- 导入到其他 app 后无法配置设备

**解决**：
- 确保从数据库读取真实的 deviceKey
- deviceKey 是配网时生成的，不能是全 0xFF

### 2. 模型绑定信息缺失

**问题**：
```json
{
  "modelId": "1002",
  "bind": [],        // ❌ 没有绑定 AppKey
  "subscribe": []
}
```

**影响**：
- 导入后无法控制设备
- 需要重新绑定 AppKey

**解决**：
- 导出时包含模型的 AppKey 绑定信息
- 从数据库读取 `model_app_key_bind` 表

### 3. Composition Data 缺失

**问题**：
```json
{
  "UUID": "...",
  "unicastAddress": "0011",
  // ❌ 缺少 cid, pid, vid, crpl
  "elements": []     // ❌ 缺少元素和模型信息
}
```

**影响**：
- 不知道设备支持哪些模型
- 无法正确配置设备

**解决**：
- 配网后立即发送 `ConfigCompositionDataGet`
- 保存 Composition Data 到数据库

### 4. 地址分配范围错误

**问题**：
```json
"allocatedUnicastRange": [
  {
    "lowAddress": "0001",  // ❌ 从 0x0001 开始
    "highAddress": "199A"
  }
]
```

**影响**：
- 可能分配到保留地址或 Provisioner 地址
- 导致地址冲突

**解决**：
- `lowAddress` 应该从 Provisioner 地址之后开始
- 或者预留低地址段（如从 0x0100 开始）

---

## 验证导出的 JSON

### 1. 检查 deviceKey

```bash
# 查看所有设备的 deviceKey
cat exported.json | grep -A 1 '"deviceKey"'
```

**正确示例**：
```
"deviceKey": "E577E2C365A3788A9875729831AF5402",
"deviceKey": "9E14231CD0E5A3F3C0B14ED86CF8DC5B",
```

**错误示例**：
```
"deviceKey": "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",  // 全 F
"deviceKey": "00000000000000000000000000000000",  // 全 0
```

### 2. 检查模型绑定

```bash
# 查看模型绑定情况
cat exported.json | grep -A 2 '"modelId"'
```

**正确示例**：
```json
{
  "modelId": "1002",
  "bind": [0],        // ✅ 绑定到 AppKey index 0
  "subscribe": []
}
```

### 3. 检查设备信息

```bash
# 查看设备是否有 cid, pid, vid
cat exported.json | grep -E '"cid"|"pid"|"vid"|"crpl"'
```

**正确示例**：
```
"cid": "07D7",
"pid": "0000",
"vid": "0000",
"crpl": "0014",
```

---

## 你的 app 可能缺失的功能

### 1. 获取 Composition Data

**问题**：配网后没有获取设备的 Composition Data

**解决**：
```kotlin
// 配网完成后
override fun onProvisioningCompleted(meshNode: ProvisionedMeshNode?, ...) {
    val address = meshNode?.unicastAddress ?: return
    
    // 获取 Composition Data
    val message = ConfigCompositionDataGet()
    meshManagerApi.createMeshPdu(address, message)
}
```

### 2. 保存 Composition Data

**问题**：收到 Composition Data 后没有保存到数据库

**解决**：
```kotlin
override fun onMeshMessageReceived(src: Int, meshMessage: MeshMessage) {
    if (meshMessage is ConfigCompositionDataStatus) {
        // nRF Mesh 库会自动保存到数据库
        // 确保数据库正确更新
        Log.d("MeshApp", "收到 Composition Data: cid=${meshMessage.companyIdentifier}")
    }
}
```

### 3. 绑定 AppKey 到模型

**问题**：配网后没有绑定 AppKey

**解决**：
```kotlin
private fun bindModels(address: Int) {
    val network = meshNetWork ?: return
    val node = network.getNode(address) ?: return
    val appKey = network.appKeys.firstOrNull() ?: return
    
    node.elements?.forEach { element ->
        element.value.meshModels?.forEach { (modelId, _) ->
            // 绑定需要控制的模型
            if (listOf(0x1000, 0x1002, 0x1100, 0x1200, 0x1206).contains(modelId)) {
                val message = ConfigModelAppBind(
                    element.value.elementAddress,
                    modelId,
                    appKey.keyIndex
                )
                meshManagerApi.createMeshPdu(address, message)
            }
        }
    }
}
```

---

## 测试导出功能

### 1. 导出 JSON

```kotlin
// 在你的 app 中
val json = viewModel.exportMeshNetwork()
// 保存到文件
File("/sdcard/Download/my_mesh_export.json").writeText(json)
```

### 2. 对比字段

```bash
# 对比你的导出和 nRF Mesh 导出
diff <(cat my_mesh_export.json | grep -o '"[^"]*":' | sort) \
     <(cat 小米9.json | grep -o '"[^"]*":' | sort)
```

### 3. 验证导入

```bash
# 将你的导出导入到 nRF Mesh app
# 如果导入成功，说明格式正确
```

---

## 总结

### 最关键的字段

1. **deviceKey** - 没有这个，配置消息无法加密
2. **模型绑定 (bind)** - 没有这个，控制消息无法工作
3. **Composition Data (cid, pid, elements)** - 没有这个，不知道设备支持什么

### 检查你的 app

运行以下检查：

```kotlin
// 1. 检查 deviceKey 是否正确
val node = network.getNode(address)
Log.d("MeshApp", "DeviceKey: ${node?.deviceKey?.joinToString("") { "%02X".format(it) }}")

// 2. 检查模型绑定
node?.elements?.forEach { element ->
    element.value.meshModels?.forEach { (modelId, model) ->
        Log.d("MeshApp", "Model 0x${modelId.toString(16)}: bound keys = ${model.boundAppKeyIndexes}")
    }
}

// 3. 检查 Composition Data
Log.d("MeshApp", "CID: ${node?.companyIdentifier}")
Log.d("MeshApp", "PID: ${node?.productIdentifier}")
```

如果这些信息都正确，导出的 JSON 应该就是完整的。
