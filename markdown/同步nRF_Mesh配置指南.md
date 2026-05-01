# 同步 nRF Mesh 配置指南

## 问题描述

nRF Mesh app 配网的设备，在你的 app 中无法控制，但在 nRF Mesh app 中正常。

**根本原因**：两个 app 使用不同的 Mesh 网络配置（NetKey、AppKey、设备信息不同步）。

---

## 解决方案 1：从 nRF Mesh app 导出配置（推荐）

### 步骤 1：在 nRF Mesh app 中导出配置

1. 打开 **nRF Mesh** app
2. 进入 **Network** 页面
3. 点击右上角 **菜单（三个点）**
4. 选择 **Export network**
5. 选择导出位置（如 Downloads 文件夹）
6. 记住导出的文件名（如 `mesh_network_export.json`）

### 步骤 2：将配置文件复制到项目

```bash
# 从手机复制到电脑
adb pull /sdcard/Download/mesh_network_export.json E:/code/android/BLE_Device_Mesh/

# 或者通过 USB 连接手机，直接复制文件
```

### 步骤 3：在你的 app 中导入配置

**方法 A：通过 UI 导入（如果你的 app 有导入功能）**

1. 打开你的 app
2. 进入设置页面
3. 点击"导入 Mesh 配置"
4. 选择从 nRF Mesh 导出的 JSON 文件

**方法 B：替换项目中的配置文件**

```bash
# 备份原配置
cp 小米9.json 小米9_backup.json

# 使用 nRF Mesh 导出的配置
cp mesh_network_export.json 小米9.json

# 重新安装 app
./gradlew installDebug
```

### 步骤 4：验证配置同步

1. 打开你的 app
2. 检查主界面显示的 Provisioner 地址
3. 尝试连接到已配网的设备
4. 发送控制命令测试

---

## 解决方案 2：在你的 app 中重新配网

如果无法导出 nRF Mesh 配置，可以在你的 app 中重新配网所有设备。

### 步骤 1：清除 nRF Mesh 的配网信息

**选项 A：在 nRF Mesh app 中删除设备**

1. 打开 nRF Mesh app
2. 进入设备列表
3. 长按设备 → 选择 **Delete node**
4. 对所有设备重复此操作

**选项 B：在固件端重置设备**

如果设备支持按键重置：
- 长按设备上的重置按钮（通常 5-10 秒）
- 设备会清除配网信息，重新进入未配网状态

### 步骤 2：在你的 app 中配网

1. 打开你的 app
2. 进入配网页面
3. 扫描未配网设备
4. 逐个配网

### 步骤 3：配置设备

配网完成后，需要：
1. 添加 AppKey 到设备
2. 绑定模型到 AppKey
3. 设置发布/订阅地址（如果需要）

---

## 解决方案 3：使用相同的 NetKey 和 AppKey

如果你知道 nRF Mesh 使用的 NetKey 和 AppKey，可以手动修改 `小米9.json`。

### 步骤 1：从 nRF Mesh 获取 Key

**方法 A：导出配置文件查看**

导出 nRF Mesh 配置后，打开 JSON 文件查看：

```json
{
  "netKeys": [
    {
      "key": "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",  // ← NetKey
      "index": 0
    }
  ],
  "appKeys": [
    {
      "key": "YYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYY",  // ← AppKey
      "index": 0,
      "boundNetKey": 0
    }
  ]
}
```

### 步骤 2：修改你的配置文件

编辑 `小米9.json`：

```json
{
  "netKeys": [
    {
      "name": "Network Key 1",
      "index": 0,
      "key": "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",  // 替换为 nRF Mesh 的 NetKey
      "phase": 0,
      "minSecurity": "insecure",
      "timestamp": "2026-04-24T00:00:00+08:00"
    }
  ],
  "appKeys": [
    {
      "name": "Application Key 1",
      "index": 0,
      "boundNetKey": 0,
      "key": "YYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYY"  // 替换为 nRF Mesh 的 AppKey
    }
  ]
}
```

### 步骤 3：同步设备信息

还需要将 nRF Mesh 中已配网的设备信息添加到 `小米9.json` 的 `nodes` 数组中。

---

## 验证配置是否同步

### 检查清单

- [ ] NetKey 相同
- [ ] AppKey 相同
- [ ] 设备地址相同
- [ ] 设备 DeviceKey 相同
- [ ] 模型绑定的 AppKey 相同

### 测试步骤

1. **连接测试**
   ```
   你的 app → 连接到设备 → 检查是否成功
   ```

2. **控制测试**
   ```
   你的 app → 发送亮度控制命令 → 检查灯是否响应
   ```

3. **日志检查**
   ```bash
   adb logcat | grep -E "MeshApp|Mesh"
   ```
   
   查看是否有错误：
   - `消息解密失败` → NetKey 或 AppKey 不匹配
   - `未找到设备` → 设备地址不在配置中
   - `发送失败` → 连接或网络问题

---

## 常见问题

### Q1: 为什么两个 app 不能共享同一个 Mesh 网络？

A: 可以共享，但需要使用相同的配置文件（NetKey、AppKey、设备信息）。

### Q2: 如果我在 nRF Mesh 中配网，能在我的 app 中控制吗？

A: 可以，前提是：
1. 导入 nRF Mesh 的配置到你的 app
2. 或者在你的 app 中使用相同的 NetKey 和 AppKey

### Q3: 如果我修改了 `小米9.json`，需要重新安装 app 吗？

A: 是的，或者在 app 中提供"重新导入配置"功能。

### Q4: 如何确认两个 app 使用的是同一个 Mesh 网络？

A: 检查以下信息是否相同：
- Mesh UUID
- NetKey
- AppKey
- Provisioner 地址

---

## 推荐工作流程

### 开发阶段

1. **使用你的 app 配网**
   - 确保 `小米9.json` 配置正确
   - 在你的 app 中配网所有设备
   - 导出配置备份

2. **测试阶段**
   - 如果需要用 nRF Mesh 测试，先导入你的配置
   - 或者在 nRF Mesh 中配网后，导出配置到你的 app

### 生产阶段

1. **统一配置管理**
   - 使用固定的 NetKey 和 AppKey
   - 所有设备使用相同的配置
   - 提供配置导入/导出功能

2. **多 app 协同**
   - 提供配置共享机制
   - 或者使用云端配置同步

---

## 调试技巧

### 查看当前 Mesh 配置

```bash
# 查看你的 app 的配置
adb shell "run-as com.example.ble_device_mesh cat databases/mesh_network_database.db" > mesh_db.bin

# 或者在 app 中添加日志
Log.d("MeshApp", "NetKey: ${network.netKeys[0].key}")
Log.d("MeshApp", "AppKey: ${network.appKeys[0].key}")
Log.d("MeshApp", "Devices: ${network.nodes.size}")
```

### 对比两个配置

```bash
# 导出 nRF Mesh 配置
nrf_mesh_export.json

# 你的 app 配置
小米9.json

# 对比 NetKey 和 AppKey
diff <(jq '.netKeys[0].key' nrf_mesh_export.json) <(jq '.netKeys[0].key' 小米9.json)
diff <(jq '.appKeys[0].key' nrf_mesh_export.json) <(jq '.appKeys[0].key' 小米9.json)
```

---

## 总结

**最简单的解决方案**：从 nRF Mesh app 导出配置，导入到你的 app。

**最彻底的解决方案**：在你的 app 中重新配网所有设备。

**最灵活的解决方案**：在你的 app 中添加配置导入/导出功能，方便与其他 app 共享配置。
