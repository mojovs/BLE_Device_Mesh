# BLE_Device_Mesh

Android BLE Mesh 设备管理应用 (Kotlin, nRF Mesh 库 3.4.0)

## 关键路径
- **源码**: `app/src/main/java/com/example/ble_device_mesh/`
- **nRF Mesh 库源码**: `E:\code\android\Android-nRF-Mesh-Library-main`
- **固件项目**: `E:\code\c\risc-v\BLE_Light_CH592\`
- **Android 测试设备**: `4e456a52`

## 路径转换
- WSL: `/e/code/...` ↔ Windows: `E:\code\...`（挂载点在根目录，不在 /mnt/）
- Linux: `/home/meng/code/android/BLE_Device_Mesh`

## 核心模块
- `MeshViewModel.kt` — Mesh 网络数据管理
- `BleConnectionManager.kt` — BLE 连接管理
- `BleScannerManager.kt` — BLE 扫描管理
- `SchedulerMessageHelper.kt` — 定时任务消息处理
- `ProvisionActivity.kt` — 设备配网
- `DeviceDetailActivity.kt` — 设备详情和控制

## 固件编译 (WSL)
```bash
"/g/Program/JetBrains/CLion 2024.1.6/bin/cmake/win/x64/bin/cmake.exe" \
  --build /e/code/c/risc-v/BLE_Light_CH592/cmake-build-ch592_debug -- -j$(nproc)
```

## Draw.io 导出命令
```bash
"D:/Program Files/draw.io/draw.io.exe" --export --format png --scale 3 --border 20 -o "输出文件.png" "源文件.drawio"
```

## Git 提交规范
`Add:` 新增 / `Fix:` 修复 / `Update:` 更新

## 调试
```bash
adb logcat -v raw -s MeshApp
```

## 文件组织
- `markdown/` — 文档 (.md)，图片放 `markdown/images/`
- `pdf/` — PDF 及硬件参考资料
- `drawio/` — 图表源文件，导出 PNG 放 `drawio/images/`

## BLE Mesh OpCode 字节序
- 固件大端定义: `#define BLE_MESH_MODEL_OP_2(b0, b1) (((b0) << 8) | (b1))` → `0x8249`
- 网络小端传输: `0x8249` 发为 `0x49 0x82`
- Android 日志显示: `8249`（无 0x 前缀）

## 固件端禁止规则
- **禁止在 Mesh 消息回调中直接写 Flash**（禁用中断，与 BLE 协议栈冲突）
- 应使用 `tmos_start_task` 延迟执行（如 `App_TriggerSchedulerSave()`）

## 配网后模型绑定问题
- ConfigAppKeyAdd/ConfigModelAppBind 可能超时跳过导致绑定不完整
- 症状: TimeSet 成功但 GenericLevelSet 无效
- 解决: 设备详情页「重新绑定模型」按钮

## MIUI BLE 写入 Bug
- `writeCharacteristic()` 返回 true 但 `onCharacteristicWrite` 不回掉，数据不发
- 根因: Xiaomi 蓝牙栈 `mDeviceBusy` 卡死
- 绕过（三合一）: (1) 写入前延迟 ≥300ms (2) 主动调用 `MeshManagerApi.handleWriteCallbacks()` (3) 配网 PDU 用 `WRITE_TYPE_NO_RESPONSE` (`forceReliable = false`)
- 诊断: logcat 出现"配网 PDU 已发送"但设备端无收到
