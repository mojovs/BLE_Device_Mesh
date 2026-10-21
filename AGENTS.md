# AGENTS.md

Android BLE Mesh 设备管理应用。单模块 `:app`（Kotlin），基于 nRF Mesh 库 3.4.0，UI 用传统 View/XML（非 Compose）。

## 构建与验证 (Windows / PowerShell)

```powershell
.\gradlew.bat :app:assembleDebug                  # 编译
adb -s 4e456a52 install -r app\build\outputs\apk\debug\app-debug.apk   # 安装到测试设备
adb -s 4e456a52 shell am start -n com.example.ble_device_mesh/.MainActivity
.\gradlew.bat :app:lintDebug                      # lint
.\gradlew.bat :app:testDebugUnitTest              # 单元测试（仅默认示例测试）
```

- 测试设备序列号 `4e456a52`（.idea 和 *.sh 脚本中硬编码，用 `adb -s` 指定）
- `gradle.properties` 指定 Android Studio JBR 为 JDK，并通过 Clash 代理 `127.0.0.1:7897` 拉依赖；首次构建/解析依赖必须能访问该代理
- Gradle 8.13 / AGP 8.13.2 / Kotlin 2.0.21 / compileSdk 36 / minSdk 24

## 关键路径

- 源码: `app/src/main/java/com/example/ble_device_mesh/`
- nRF Mesh 库源码: `E:\code\android\Android-nRF-Mesh-Library-main`（排查协议栈行为时直接读源码）
- 固件项目: `E:\code\c\risc-v\BLE_Light_CH592`（CH592 RISC-V，设备端逻辑）
- WSL 挂载: `/e/code/...` ↔ `E:\code\...`

## 核心模块

- `MeshViewModel.kt` — Mesh 网络数据管理
- `BleConnectionManager.kt` — BLE 连接（含 MIUI 兼容处理）
- `BleScannerManager.kt` — BLE 扫描
- `SchedulerMessageHelper.kt` — 定时任务消息封装
- `data/` — 本地仓库与实体（`SchedulerRepository`、`StreetlightPresetRepository`、`RadarDetectionRepository` 等）
- `ProvisionActivity.kt` / `DeviceDetailActivity.kt` — 配网与设备控制

## BLE Mesh 协议要点（易踩坑）

- **OpCode 字节序**: 固件大端定义（`0x8249`），网络小端传输（发出 `0x49 0x82`）；Android 日志显示无前缀 `8249`
- **配网后模型绑定**: `ConfigAppKeyAdd`/`ConfigModelAppBind` 可能超时跳过导致绑定不完整 → 症状为 TimeSet 成功但 GenericLevelSet 无效；设备详情页有「重新绑定模型」按钮
- **MIUI BLE 写入 Bug**: `writeCharacteristic()` 返回 true 但 `onCharacteristicWrite` 不回调、数据不发。绕过（三合一）：(1) 写入前延迟 ≥300ms (2) 主动调用 `MeshManagerApi.handleWriteCallbacks()` (3) 配网 PDU 用 `WRITE_TYPE_NO_RESPONSE` (`forceReliable = false`)。诊断: logcat 出现"配网 PDU 已发送"但设备端无收到

## 固件端约束（涉及联动开发时）

- 禁止在 Mesh 消息回调中直接写 Flash（禁用中断与 BLE 协议栈冲突），用 `tmos_start_task` 延迟执行（如 `App_TriggerSchedulerSave()`）

## 调试

```powershell
adb -s 4e456a52 logcat -v raw -s MeshApp
```

根目录 `test_provision.sh` / `diagnose_provision.sh` / `diagnose_temperature.sh` 为现成的配网/故障诊断脚本。

## 文档组织

- `markdown/` — 技术文档，图片放 `markdown/images/`
- `drawio/` — 图表源文件，导出 PNG 放 `drawio/images/`
- `SCHEDULER_SPEC.md` / `STREETLIGHT_SPEC.md` — 定时任务与路灯功能规格（改动相关功能前先读）
- 根目录两个 draw.io 安装均可用于导出：`D:\Program Files\draw.io\draw.io.exe` 与 `C:\Program Files\draw.io\draw.io.exe`

## Git 约定

- 提交规范: `Add:` 新增 / `Fix:` 修复 / `Update:` 更新
