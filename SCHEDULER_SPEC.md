# 定时开关灯功能 - 完整规格与实现计划

## 文档信息
- **版本**: v1.0
- **创建日期**: 2026-04-18
- **作者**: Kiro (AI Agent)
- **项目**: BLE_Device_Mesh
- **固件路径**: ~/code/riscv/BLE_Light_CH592/
- **App 路径**: /home/meng/code/android/BLE_Device_Mesh/

---

## 1. 功能概述

### 1.1 目标
在现有 BLE Mesh 固件和 Android App 基础上，实现完整的定时开关灯功能，支持通过手机 App 设置、管理和监控设备的定时任务。

### 1.2 用户需求总结
- **任务数量**: 16个定时任务（固件已支持）
- **操作类型**: 开/关 + 亮度控制（0-100%）
- **重复模式**: 每天重复 + 指定星期几 + 一次性执行
- **时间精度**: 精确到分钟
- **UI位置**: 设备详情页添加"定时任务"按钮
- **任务管理**: 支持启用/禁用开关
- **时间同步**: 每小时自动同步手机时间到设备
- **数据持久化**: 任务保存到 Flash，断电不丢失
- **执行反馈**: 通过 Scheduler Action Status 消息通知 App
- **执行历史**: 显示最近 10 次执行记录
- **冲突处理**: 手动控制优先，但不影响下次定时执行
- **时区**: 使用手机当前时区

---

## 2. 功能需求规格

### 2.1 定时任务属性

每个定时任务包含以下属性：

| 属性 | 类型 | 范围 | 说明 |
|------|------|------|------|
| **索引 (Index)** | uint8 | 0-15 | 任务唯一标识 |
| **时间 (Time)** | HH:MM | 00:00-23:59 | 执行时间（分钟精度） |
| **动作 (Action)** | enum | ON/OFF | 开灯或关灯 |
| **亮度 (Brightness)** | uint8 | 0-100 | 亮度百分比（仅 ON 时有效） |
| **重复规则 (Repeat)** | bitmask | 0x00-0x7F | bit0-6 对应周日-周六，0x00=一次性 |
| **启用状态 (Enabled)** | bool | 0/1 | 是否启用该任务 |
| **执行日期 (Date)** | YYYY-MM-DD | - | 一次性任务的执行日期 |

### 2.2 重复规则编码

```
Bit 0: 周日
Bit 1: 周一
Bit 2: 周二
Bit 3: 周三
Bit 4: 周四
Bit 5: 周五
Bit 6: 周六

示例:
0x00 = 一次性（需配合执行日期）
0x7F = 每天 (0111 1111)
0x3E = 工作日 (0011 1110 = 周一到周五)
0x41 = 周末 (0100 0001 = 周六+周日)
0x2A = 周一、三、五 (0010 1010)
```

### 2.3 任务执行逻辑

1. **时间匹配**: 每分钟检查一次（秒=0时触发）
2. **日期匹配**:
   - 如果 `repeat == 0x00`，检查当前日期是否等于 `date`
   - 如果 `repeat != 0x00`，检查当前星期是否在 bitmask 中
3. **启用检查**: 只执行 `enabled == 1` 的任务
4. **动作执行**:
   - `Action == ON`: 调用 `gen_level_force_set_percent(brightness)`
   - `Action == OFF`: 调用 `gen_level_force_set_percent(0)`
5. **执行通知**: 发送 `Scheduler Action Status` 消息到 App
6. **历史记录**: 保存执行时间戳到 Flash（最多10条）

---

## 3. 固件实现规格

### 3.1 现有代码评估

**已实现功能**:
- ✅ Scheduler Server Model (0x1206) 已注册
- ✅ 16个任务槽位 (`my_schedules[16]`)
- ✅ 标准 Mesh OpCode 支持 (0x8249/0x824A/0x60/0x61)
- ✅ 时间解析（时/分/秒）
- ✅ 基本执行逻辑 (`scheduler_check_and_execute`)
- ✅ 亮度控制接口 (`gen_level_force_set_percent`)

**需要扩展**:
- ❌ 亮度参数（当前固定50%）
- ❌ 星期重复规则
- ❌ 一次性任务日期
- ❌ 启用/禁用状态
- ❌ Flash 持久化
- ❌ 执行通知消息
- ❌ 执行历史记录

### 3.2 数据结构扩展

修改 `app_scheduler_model.h`:

```c
typedef struct {
    uint8_t  index;        // 0-15
    uint8_t  hour;         // 0-23
    uint8_t  minute;       // 0-59
    uint8_t  action;       // 0=OFF, 1=ON
    uint8_t  brightness;   // 0-100 (百分比)
    uint8_t  repeat;       // bit0-6: 周日-周六, 0x00=一次性
    uint8_t  enabled;      // 0=禁用, 1=启用
    uint16_t year;         // 一次性任务的年份
    uint8_t  month;        // 一次性任务的月份 (1-12)
    uint8_t  day;          // 一次性任务的日期 (1-31)
    uint32_t last_exec;    // 最后执行时间戳 (用于防重复触发)
} scheduler_entry_t;

typedef struct {
    uint32_t timestamp;    // 执行时间戳
    uint8_t  index;        // 任务索引
    uint8_t  action;       // 执行的动作
    uint8_t  brightness;   // 执行的亮度
} scheduler_history_t;

extern scheduler_history_t exec_history[10];  // 最近10次执行记录
extern uint8_t history_count;                 // 当前记录数
```

### 3.3 Mesh 消息编码

使用标准 Scheduler Action Set 的 10 字节格式，扩展字段：
- **Byte 0**: Index (bits 0-3)
- **Byte 1-2**: Year (0x64 = 任意年份)
- **Byte 3**: Month (低4位) + Hour (高4位)
- **Byte 4**: Hour高位 + Minute + Second低位
- **Byte 5**: Second高5位 + DayOfWeek低3位
- **Byte 6**: DayOfWeek高4位 + Action (高4位)
- **Byte 7**: ~~TransitionTime~~ → **Brightness (0-100)**
- **Byte 8**: ~~SceneNumber低字节~~ → **Enabled (0/1)**
- **Byte 9**: ~~SceneNumber高字节~~ → 保留

---

## 4. Android App 实现规格

### 4.1 数据模型

```kotlin
data class SchedulerTask(
    val index: Int,              // 0-15
    val hour: Int,               // 0-23
    val minute: Int,             // 0-59
    val action: Action,          // ON/OFF
    val brightness: Int,         // 0-100
    val repeat: Int,             // bitmask
    val enabled: Boolean,
    val date: LocalDate? = null  // 一次性任务的日期
) {
    enum class Action { OFF, ON }
    
    fun getRepeatDescription(): String {
        return when (repeat) {
            0x00 -> "一次性 (${date?.toString() ?: "未设置"})"
            0x7F -> "每天"
            0x3E -> "工作日"
            0x41 -> "周末"
            else -> {
                val days = listOf("日", "一", "二", "三", "四", "五", "六")
                days.filterIndexed { i, _ -> (repeat and (1 shl i)) != 0 }
                    .joinToString("、") { "周$it" }
            }
        }
    }
}
```

### 4.2 UI 设计

#### 任务列表页 (SchedulerListActivity)
- RecyclerView 显示任务列表
- FloatingActionButton 添加新任务
- 每项显示：时间、动作图标、亮度、重复规则、启用开关
- 长按菜单：编辑/删除

#### 任务编辑页 (SchedulerEditActivity)
- TimePicker 选择时间
- RadioGroup 选择动作（开/关）
- SeekBar 选择亮度（开灯时显示）
- CheckBox 选择星期（周日-周六）
- DatePicker 选择日期（一次性任务）
- Switch 启用开关
- 保存/取消按钮

---

## 5. 测试计划

### 5.1 单元测试
- 固件：时间匹配、星期匹配、Flash 读写
- App：消息编码/解码、重复规则描述

### 5.2 集成测试
- 设置任务后查询验证
- 断电重启后任务保留
- 时间同步后按时执行
- 手动控制不影响定时

### 5.3 用户场景测试
1. 每天定时开关灯
2. 工作日闹钟灯
3. 一次性提醒
4. 临时禁用任务

---

## 6. 技术约束

- Flash 擦写次数有限（约10万次），需合并写入
- TMOS 定时器精度约 ±1秒
- Mesh 消息长度限制 11 字节
- nRF Mesh 库可能不完全支持 Scheduler Model
- Android 后台服务受限

---

## 7. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| nRF Mesh 库不支持 Scheduler Set | 高 | 使用反射或 Vendor Model |
| 时间同步失败 | 中 | 添加手动同步按钮 |
| Flash 写入过于频繁 | 中 | 批量写入，延迟保存 |
| 多任务冲突 | 低 | 按索引顺序执行 |



---

# 实现计划

## 问题陈述
当前固件已实现基础的 Scheduler Model，但缺少关键功能：亮度控制、星期重复规则、启用/禁用状态、Flash 持久化、执行通知等。Android App 端完全缺少定时任务管理界面。

## 架构设计
```
固件侧：
  scheduler_entry_t (扩展) → Flash 存储 → 执行引擎 → 通知消息
                                ↓
                          每分钟检查 + 星期匹配

App 侧：
  UI (列表/编辑) → ViewModel → MessageHelper → Mesh 网络
         ↓                           ↑
    本地缓存 ←─────────────── 状态同步
```

---

## 任务分解

### Task 1: 固件数据结构扩展
**目标**: 扩展 `scheduler_entry_t` 结构体，支持亮度、星期规则、启用状态

**实现**:
1. 修改 `app_scheduler_model.h`:
   - 添加 `brightness` (uint8, 0-100)
   - 添加 `repeat` (uint8, bitmask)
   - 添加 `enabled` (uint8, 0/1)
   - 添加 `last_exec` (uint32, 时间戳)
2. 修改 `scheduler_model_init()` 初始化新字段
3. 修改 `sched_action_set()` 解析新字段（从 Byte 7-8）
4. 修改 `sched_action_get()` 编码新字段

**测试**: 
- 通过串口打印验证结构体大小
- 设置任务后查询，验证字段正确

**Demo**: 固件能接收并存储包含亮度和星期规则的任务

---

### Task 2: 固件执行逻辑增强
**目标**: 实现星期匹配、启用检查、亮度控制

**实现**:
1. 添加 `get_current_weekday()` 函数（基于 `mesh_time_2_local`）
2. 修改 `scheduler_check_and_execute()`:
   - 只在秒=0时检查（避免重复触发）
   - 检查 `enabled` 标志
   - 检查 `repeat` bitmask 与当前星期匹配
   - 使用 `task->brightness` 而非固定值
   - 更新 `last_exec` 防止同一分钟重复执行
3. 添加调试日志

**测试**:
- 设置"周一、三、五 14:30 开灯 80%"
- 验证只在指定星期执行
- 验证亮度正确

**Demo**: 固件能按星期规则执行任务，亮度可控

---

### Task 3: Flash 持久化
**目标**: 任务数据保存到 Flash，断电不丢失

**实现**:
1. 定义 Flash 地址 `FLASH_SCHEDULER_ADDR 0x70000`
2. 实现 `save_scheduler_to_flash()`:
   - 擦除 4KB 扇区
   - 写入 `my_schedules` 数组
3. 实现 `load_scheduler_from_flash()`:
   - 读取数据
   - 验证有效性（时间范围检查）
4. 在 `scheduler_model_init()` 中调用加载
5. 在 `sched_action_set()` 后延迟保存（使用 TMOS 定时器）

**测试**:
- 设置任务 → 断电重启 → 查询任务仍存在

**Demo**: 设备重启后任务配置保留

---

### Task 4: 执行通知消息
**目标**: 任务执行后发送 Scheduler Action Status 通知 App

**实现**:
1. 添加 `send_scheduler_notification(uint8_t index)`:
   - 构造 `BLE_MESH_MODEL_OP_SCHEDULER_ACT_STATUS` 消息
   - 目标地址从配置获取（或广播到 0xFFFF）
   - 调用 `bt_mesh_model_send()`
2. 在 `scheduler_check_and_execute()` 执行后调用
3. 添加日志验证发送成功

**测试**:
- App 监听消息
- 任务执行时 App 收到通知

**Demo**: 任务执行时 App 实时显示通知

---

### Task 5: 执行历史记录
**目标**: 记录最近 10 次执行，保存到 Flash

**实现**:
1. 定义 `scheduler_history_t` 结构体
2. 添加全局数组 `exec_history[10]` 和 `history_count`
3. 实现 `add_execution_history()`:
   - 循环覆盖（FIFO）
   - 记录时间戳、索引、动作、亮度
4. 在 Flash 中预留空间存储历史
5. 实现查询历史的 Vendor Model 消息（OpCode 自定义）

**测试**:
- 执行 15 次任务
- 查询历史，验证只保留最近 10 次

**Demo**: 固件能查询执行历史

---

### Task 6: App 数据模型与 Repository
**目标**: 创建 Kotlin 数据类和本地存储

**实现**:
1. 创建 `SchedulerTask.kt` 数据类:
   - 包含所有字段
   - 实现 `getRepeatDescription()` 方法
2. 创建 `SchedulerRepository.kt`:
   - 使用 SharedPreferences 或 Room 数据库
   - 实现 CRUD 方法
   - 缓存每个设备的任务列表
3. 在 `MeshViewModel` 添加 LiveData:
   - `schedulerTasks: MutableLiveData<List<SchedulerTask>>`

**测试**:
- 单元测试 Repository 的增删改查
- 验证数据持久化

**Demo**: App 能本地存储任务列表

---

### Task 7: App 消息编码/解码
**目标**: 扩展 `SchedulerMessageHelper` 支持新字段

**实现**:
1. 实现 `createSchedulerActionSet()`:
   - 按 10 字节格式编码
   - Byte 7 = brightness
   - Byte 8 = enabled
   - 使用反射创建 `AccessMessage`（如果库不支持）
2. 实现 `parseSchedulerActionStatus()`:
   - 解析 10 字节数据
   - 返回 `SchedulerTask` 对象
3. 在 `MeshViewModel` 添加发送/接收方法

**测试**:
- 单元测试编码/解码一致性
- 发送消息到固件，验证解析正确

**Demo**: App 能正确发送和接收任务数据

---

### Task 8: 任务列表 UI
**目标**: 创建任务列表页面

**实现**:
1. 创建 `SchedulerListActivity.kt`:
   - RecyclerView 显示任务列表
   - FloatingActionButton 添加任务
   - 每项显示：时间、动作图标、亮度、重复规则、启用开关
2. 创建 `SchedulerAdapter.kt`:
   - ViewHolder 绑定数据
   - 点击编辑，长按删除
   - Switch 切换启用状态
3. 创建布局文件:
   - `activity_scheduler_list.xml`
   - `item_scheduler_task.xml`
4. 在 `DeviceDetailActivity` 添加入口按钮

**测试**:
- 显示空列表
- 显示多个任务
- 点击/长按交互

**Demo**: 能查看设备的任务列表

---

### Task 9: 任务编辑 UI
**目标**: 创建任务添加/编辑页面

**实现**:
1. 创建 `SchedulerEditActivity.kt`:
   - TimePicker 选择时间
   - RadioGroup 选择动作（开/关）
   - SeekBar 选择亮度（开灯时显示）
   - CheckBox 选择星期（周日-周六）
   - DatePicker 选择日期（一次性任务）
   - Switch 启用开关
   - 保存/取消按钮
2. 创建布局 `activity_scheduler_edit.xml`
3. 实现数据验证（时间冲突检测）
4. 保存时调用 `viewModel.setSchedulerTask()`

**测试**:
- 添加新任务
- 编辑现有任务
- 验证输入合法性

**Demo**: 能添加和编辑任务

---

### Task 10: 时间同步服务
**目标**: 每小时自动同步时间到所有设备

**实现**:
1. 创建 `TimeSyncService.kt`:
   - 前台服务（显示通知）
   - Handler 每小时触发
   - 遍历所有已配网设备
   - 调用 `viewModel.sendTimeSet(address)`
2. 在 `MainActivity` 启动服务
3. 添加服务到 `AndroidManifest.xml`
4. 添加前台服务权限

**测试**:
- 启动服务后验证定时触发
- 验证多设备同步

**Demo**: 时间自动同步，任务按时执行

---

### Task 11: 执行通知接收
**目标**: App 接收并显示任务执行通知

**实现**:
1. 在 `MeshViewModel.onMeshMessageReceived()` 添加处理:
   - 识别 `SCHEDULER_ACT_STATUS` 消息
   - 解析任务信息
   - 更新 LiveData
2. 在 `SchedulerListActivity` 观察 LiveData:
   - 显示 Toast 或 Snackbar
   - 更新列表项状态
3. 添加通知声音/震动（可选）

**测试**:
- 任务执行时 App 显示通知

**Demo**: 实时看到任务执行反馈

---

### Task 12: 执行历史 UI
**目标**: 显示任务执行历史

**实现**:
1. 在 `SchedulerListActivity` 添加"历史"按钮
2. 创建 `SchedulerHistoryActivity.kt`:
   - RecyclerView 显示历史记录
   - 每项显示：时间、任务索引、动作、亮度
3. 实现查询历史的 Mesh 消息（Vendor Model）
4. 解析历史数据并显示

**测试**:
- 执行多次任务后查看历史

**Demo**: 能查看执行历史

---

### Task 13: 一次性任务支持
**目标**: 支持指定日期的一次性任务

**实现**:
1. 固件添加日期字段（年/月/日）
2. 固件执行时检查日期匹配
3. 执行后自动禁用任务
4. App 编辑页添加日期选择器
5. 当 `repeat == 0x00` 时显示日期选择

**测试**:
- 设置明天的任务
- 验证执行后自动禁用

**Demo**: 一次性任务正常工作

---

### Task 14: UI 优化与错误处理
**目标**: 提升用户体验

**实现**:
1. 添加加载动画（发送消息时）
2. 添加错误提示（超时、失败）
3. 添加任务冲突提示
4. 添加时间同步状态指示
5. 优化列表动画（添加/删除）
6. 添加空状态提示（无任务时）

**测试**:
- 各种错误场景
- 网络延迟场景

**Demo**: 流畅的用户体验

---

### Task 15: 完整测试与文档
**目标**: 全面测试并完善文档

**实现**:
1. 执行规格说明书中的所有测试场景
2. 性能测试（Flash 写入次数、内存占用）
3. 长期稳定性测试（运行 7 天）
4. 更新 CLAUDE.md 和 README
5. 创建用户手册

**测试**:
- 所有功能正常
- 无内存泄漏
- 无崩溃

**Demo**: 生产就绪的完整功能

---

## 实施建议

### 优先级
- **高优先级**: Task 1-4, 6-9 (核心功能)
- **中优先级**: Task 5, 10-11 (增强功能)
- **低优先级**: Task 12-15 (优化与完善)

### 预计工作量
- 固件侧：约 2-3 天
- App 侧：约 3-4 天
- 测试与优化：约 1-2 天
- **总计**: 约 6-9 天

### 风险点
1. nRF Mesh 库兼容性 → 提前验证反射方案
2. Flash 擦写寿命 → 实现延迟批量写入
3. 时间同步可靠性 → 添加手动同步按钮

---

## 附录：代码示例

### 固件侧核心代码片段

```c
// scheduler_check_and_execute() 伪代码
void scheduler_check_and_execute(void) {
    uint8_t hour, min, sec;
    mesh_time_2_local(&g_time_state, &hour, &min, &sec);
    
    if (sec != 0) return;  // 只在每分钟的第0秒检查
    
    uint8_t weekday = get_current_weekday();  // 0=周日
    
    for (int i = 0; i < 16; i++) {
        scheduler_entry_t *task = &my_schedules[i];
        
        if (!task->enabled) continue;
        if (task->hour != hour || task->minute != min) continue;
        
        // 检查星期匹配
        if (task->repeat != 0x00 && !(task->repeat & (1 << weekday))) continue;
        
        // 执行动作
        uint8_t brightness = (task->action == SCHED_ACTION_ON) ? task->brightness : 0;
        gen_level_force_set_percent(brightness);
        
        // 发送通知
        send_scheduler_notification(i);
    }
}
```

### App 侧核心代码片段

```kotlin
// SchedulerMessageHelper.createSchedulerActionSet() 伪代码
fun createSchedulerActionSet(appKey: ApplicationKey, task: SchedulerTask): MeshMessage? {
    val params = ByteArray(10)
    params[0] = task.index.toByte()
    // ... 编码其他字段
    params[7] = task.brightness.toByte()  // 扩展字段
    params[8] = if (task.enabled) 0x01 else 0x00  // 扩展字段
    
    return createCustomMessage(0x60, params, appKey)
}
```

---

**文档结束**
