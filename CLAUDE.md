# BLE_Device_Mesh 项目说明

## 项目简介
基于 Android 平台的低功耗蓝牙 Mesh 设备管理应用，用于控制和管理 BLE Mesh 网络中的智能设备（如灯光、传感器等）。

## 项目信息
- **应用包名**: com.example.ble_device_mesh
- **版本**: 1.0 (versionCode: 1)
- **平台**: Android
- **开发语言**: Kotlin
- **协议**: BLE Mesh
- **构建工具**: Gradle (Kotlin DSL)
- **nRF Mesh 库版本**: 3.4.0 (no.nordicsemi.android:mesh)
- **nRF Mesh 库源码路径**: E:\code\android\Android-nRF-Mesh-Library-main
- **固件项目路径**: 
  - Windows: E:\code\c\risc-v\BLE_Light_CH592\ 或 /mnt/e/code/c/risc-v/BLE_Light_CH592/ (WSL)
  - Linux: ~/code/riscv/BLE_Light_CH592/

## 项目结构

### 核心模块
- **app/src/main/java/com/example/ble_device_mesh/** - 主要源码目录
  - `MainActivity.kt` - 主界面，设备列表和扫描
  - `DeviceDetailActivity.kt` - 设备详情和控制界面
  - `ProvisionActivity.kt` - 设备配网界面
  - `SettingsActivity.kt` - 设置界面
  - `MeshViewModel.kt` - Mesh 网络数据管理
  - `BleConnectionManager.kt` - BLE 连接管理
  - `BleScannerManager.kt` - BLE 扫描管理
  - `SchedulerMessageHelper.kt` - 定时任务消息处理
  - `data/` - 数据模型目录
  - `ui/` - UI 组件目录

### 适配器
- `MeshDeviceAdapter.kt` - Mesh 设备列表适配器
- `DeviceAdapter.kt` - 设备列表适配器
- `UnprovisionedDeviceAdapter.kt` - 未配网设备适配器

### 资源文件
- **app/src/main/res/** - Android 资源目录
  - `layout/` - 布局文件
  - `values/` - 值资源（主题、字符串等）

## 主要功能
1. BLE Mesh 设备扫描和发现
2. 设备配网（Provision）
3. 设备控制（开关、亮度、色温等）
4. 定时任务管理
5. 温度和光感传感器数据读取
6. 设备列表管理（支持长按删除）

## 文件组织规则

### 文档文件
- **markdown/** - 存放所有 Markdown 文档
  - `markdown/images/` - Markdown 文档相关的图片资源
  
- **pdf/** - 存放所有 PDF 文档和硬件参考资料
  - 包括芯片手册、电路图等
  
- **drawio/** - 存放所有 Draw.io 图表文件
  - `drawio/images/` - Draw.io 导出的图片文件

### 项目文档
- **FIRMWARE_DEBUG_GUIDE.md** - 固件调试指南
- **Time_Model_修复记录.md** - Time Model 功能修复记录
- **Sensor_Model_数据格式分析.md** - 传感器模型数据格式分析
- **温度获取问题诊断.md** - 温度传感器问题诊断文档
- **快速诊断指南.md** - 快速问题诊断指南

### 文件整理原则
1. 根目录保持整洁，文档类文件按类型归档到对应目录
2. 图片文件放在对应文档类型的 images 子目录下
3. 硬件相关的图片（如电路图）放在 pdf 目录
4. Draw.io 导出的 PNG 图片放在 drawio/images 目录

### Draw.io 工作流程
1. 使用 Draw.io 创建或编辑图表文件（.drawio）
2. 图表文件保存在 `drawio/` 目录
3. 完成后使用 Draw.io 导出为 PNG 格式
4. 导出的 PNG 图片保存在 `drawio/images/` 目录

### Draw.io 导出命令
```bash
"D:/Program Files/draw.io/draw.io.exe" --export --format png --scale 3 --border 20 -o "输出文件.png" "源文件.drawio"
```
- `--scale 3` — 3 倍缩放导出，保证高清（默认 1x 会模糊）
- `--border 20` — 四周留 20px 边距
- `-o` — 输出文件路径

## 开发环境
- **平台**: Windows 10 IoT Enterprise LTSC 2021 / Linux
- **IDE**: Android Studio (推荐)
- **JDK**: 根据 gradle.properties 配置
- **Android SDK**: 根据 build.gradle.kts 配置
- **Git**: 版本控制
- **Python 环境**: D:/Tools/conda (Windows)
- **Draw.io 路径**: D:/Program Files/draw.io/draw.io.exe (Windows)
- **Android 测试设备**: 4e456a52

## 开发工具脚本

### 诊断脚本
- **diagnose_temperature.sh** - 温度传感器诊断脚本
  - 用法: `bash diagnose_temperature.sh`
  - 功能: 快速诊断温度传感器相关问题

### Python 工具（可选）
如需使用以下 Python 工具，请确保已配置 Python 环境（D:/Tools/conda）

- **串口监控工具**: `tools/serial_monitor.py`
  - 端口: COM3 (Windows) / /dev/ttyUSB0 (Linux)
  - 波特率: 115200
  - 用法: `python tools/serial_monitor.py [--log output.log]`
  - 功能: 监控串口输出，用于调试固件端

- **截图工具**: Snipaste 助手 `tools/snipaste_helper.py`
  - Snipaste 路径: G:/Program/Snipaste-2.10.5-x86/Snipaste.exe
  - 用法: `python tools/snipaste_helper.py [-m mode] [-o output_dir]`
  - 功能: 使用 Snipaste 命令行直接截图并保存到项目目录
  - 模式: 
    - `normal` - 手动选区(默认)
    - `full` - 全屏截图
    - `window` - 当前活动窗口
    - `last` - 重复上次截图区域
  - 输出目录: 
    - 默认: `markdown/images/`
    - 图表相关: `drawio/images/`
  - 文件命名: `screenshot_YYYYMMDD_HHMMSS.png`

- **网页获取工具**: `tools/web_fetch.py`
  - 用法: `python tools/web_fetch.py <url> [-o output_file]`
  - 功能: 使用 requests 库获取网页内容

- **图片转 API 格式工具**: `tools/image_to_api_format.py`
  - 用法: `python tools/image_to_api_format.py <image_path> [--text "描述文本"]`
  - 功能: 将图片转换为 Claude API 所需的 base64 格式 JSON
  - 支持格式: JPG/JPEG, PNG, GIF, WebP

## Git 工作流
- **主分支**: master
- **提交规范**: 
  - Add: 新增功能
  - Fix: 修复问题
  - Update: 更新功能
  - 版本号格式: 0.x

## 最近更新
- 添加光感传感器支持
- 调整温度控制曲线
- 主界面支持长按删除设备
- 更新 UI 并添加定时功能
- 支持自定义选择设备 MAC 地址

## BLE Mesh 协议规范

### OpCode 字节序规则
- **固件端 (MESH_LIB.h)**: 使用大端序定义 OpCode
  - 宏定义: `#define BLE_MESH_MODEL_OP_2(b0, b1) (((b0) << 8) | (b1))`
  - 示例: `BLE_MESH_MODEL_OP_2(0x82, 0x49)` = `0x8249`
  - **重要**: 不要修改 MESH_LIB.h 中的 OpCode 定义，这是固件库的标准定义

- **Android 端 (nRF Mesh 库)**: 使用小端序传输 OpCode
  - 传输格式: 低字节在前，高字节在后
  - 示例: OpCode `0x8249` 在网络上传输为 `0x49 0x82`
  - 日志显示: Android 日志中的 OpCode 通常显示为十六进制（可能无 0x 前缀）

- **OpCode 匹配机制**:
  - 固件端的 `bt_mesh_model_op` 结构体中的 opcode 字段是 uint32_t 类型
  - Mesh 协议栈会自动处理字节序转换，将接收到的小端序 OpCode 转换为大端序进行匹配
  - 因此固件端和 Android 端的 OpCode 定义看起来不同，但实际上是匹配的

### Scheduler Model OpCode 定义
- **SCHEDULER_GET**: 
  - 固件端定义: `BLE_MESH_MODEL_OP_2(0x82, 0x49)` = `0x8249`
  - 网络传输: `0x49 0x82` (小端序)
  - Android 日志显示: `8249` (十六进制，无 0x 前缀)

- **SCHEDULER_ACTION_GET**:
  - 固件端定义: `BLE_MESH_MODEL_OP_2(0x82, 0x48)` = `0x8248`
  - 网络传输: `0x48 0x82` (小端序)

- **SCHEDULER_ACTION_SET**:
  - 固件端定义: `BLE_MESH_MODEL_OP_1(0x60)` = `0x60`
  - 网络传输: `0x60` (单字节)

## 注意事项
1. 修改代码前先阅读相关文档
2. BLE Mesh 相关操作需要理解协议规范
3. 传感器数据格式参考 Sensor_Model_数据格式分析.md
4. 遇到问题先查看对应的诊断文档
5. **禁止修改固件端的 MESH_LIB.h 文件**，这是固件库的核心定义
6. **固件端 Flash 操作规则**：
   - **禁止在 Mesh 消息回调中直接写入 Flash**，Flash 写入会禁用中断，与 BLE 协议栈冲突导致死机
   - **禁止在 Mesh 消息回调中执行长时间阻塞操作**，会导致 BLE 连接超时或看门狗复位
   - **正确做法**：使用 TMOS 延迟任务（`tmos_start_task`）异步执行 Flash 写入操作
   - **示例**：在 `sched_action_set` 中调用 `App_TriggerSchedulerSave()` 触发延迟保存，而不是直接调用 `save_scheduler_to_flash()`
7. **配网后模型绑定问题**：
   - 自研配网功能使用 nRF Mesh 库导入的 NetKey/AppKey，配网后设备可能无法立即控制
   - **原因**：配置流程（ConfigAppKeyAdd → ConfigModelAppBind）使用超时机制"超时跳过"，绑定命令可能未实际到达设备
   - **症状**：TimeSet 能成功（Time Server 绑上了）但 GenericLevelSet 无效
   - **解决方法**：设备详情页点击「重新绑定模型」按钮，会重新发送 ConfigAppKeyAdd 和 ConfigModelAppBind
   - **相关模型**：0x1002 (Generic Level Server)、0x1100 (Sensor Server)、0x1200 (Time Server)、0x1206/0x1207 (Scheduler Server/Setup)

## 调试建议
1. 使用 Android Studio 的 Logcat 查看日志
2. 使用以下命令过滤 Mesh 应用日志（不带时间戳）:
   ```bash
   adb logcat -v raw -s MeshApp
   ```
3. BLE 相关问题可以使用 nRF Connect 等工具辅助调试
4. Mesh 网络问题参考 FIRMWARE_DEBUG_GUIDE.md
5. 温度传感器问题使用 diagnose_temperature.sh 快速诊断

## 已知平台问题

### MIUI (Xiaomi/Redmi) BLE 写入 Bug
- **症状**: `BluetoothGatt.writeCharacteristic()` 返回 `true`，但 `onCharacteristicWrite` **不会回调**，数据也**不会实际发送**到 BLE 设备
- **根因**: Xiaomi 蓝牙协议栈的 `mDeviceBusy` 标志卡死，导致写入请求被静默丢弃
- **影响范围**: MIUI 系统的小米/红米手机（测试机 MI 9 Android 11 确认存在）
- **AOSP 修复**: Xiaomi 已提交修复 commit `67158cb9`，合入 Android 13+，部分 MIUI 14 版本已修复
- **受影响场景**: 
  - 配网（Provisioning）PDU 写入使用 `WRITE_TYPE_DEFAULT` 时
  - BLE 连接/服务发现后立即写入时
- **解决/绕过方案**（三个方案必须同时使用，缺一不可）:
  1. **在 BLE 写入前加延迟** — 服务发现后延迟 300ms 再调用 `identifyNode()`，每次 PDU 写入前动态计算延迟确保相邻写入间隔 ≥300ms（`Handler.postDelayed`）
  2. **直接调用 `handleWriteCallbacks`** — 不依赖 `onCharacteristicWrite` 回调来推进状态机，在 `sendData()` 成功后立即调用 `MeshManagerApi.handleWriteCallbacks(mtu, pdu)`
  3. **使用 `WRITE_TYPE_NO_RESPONSE`** — 绕过需要 ATT 响应的写入路径。配网 PDU 必须用 `forceReliable = false`，否则 MIUI 协议栈的 `mDeviceBusy` 卡死会导致公钥等配网 PDU 被静默丢弃，配网卡在 "发送公钥..." 状态
- **诊断方法**: 观察 logcat 中 `"配网 PDU 已发送"` 出现但设备端从未收到数据（串口无输出），且 `onCharacteristicWrite` 无对应日志

## 路径转换规则
- WSL 路径格式: `/mnt/[盘号]` 对应 Windows 路径格式: `[盘符]:`
- 示例: `/mnt/e` → `E:`, `/mnt/d` → `D:`
- 当前项目路径: 
  - Windows: `E:\code\android\BLE_Device_Mesh`
  - WSL: `/mnt/e/code/android/BLE_Device_Mesh`
  - Linux: `/home/meng/code/android/BLE_Device_Mesh`
