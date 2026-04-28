# 路灯模式功能 - 完整规格与实现计划

## 文档信息
- **版本**: v1.0
- **创建日期**: 2026-04-19
- **项目**: BLE_Device_Mesh
- **固件路径**: ~/code/riscv/BLE_Light_CH592/
- **App 路径**: /home/meng/code/android/BLE_Device_Mesh/

---

## 1. 功能概述

### 1.1 目标
在现有 BLE Mesh Scheduler 模型基础上，实现"路灯模式"功能。用户在手机端通过时间-亮度二维曲线图设置灯光随时间的变化规律，配置下发到设备端由固件自动执行，灯光可按分钟粒度平滑渐变。

### 1.2 核心需求
- **执行方式**: 手机端配置曲线 → 下发到设备端执行，设备端独立运行，不依赖手机在线
- **曲线形态**: 多点折线，相邻控制点之间线性插值，每分钟调整一次亮度
- **控制点**: 最多 4-6 个，每个控制点定义一个(时间, 亮度)坐标
- **交互方式**: 时间-亮度二维曲线图，X 轴 0-24 时，Y 轴 0-100% 亮度，拖动控制点调整
- **白天行为**: 白天固定亮度或关灯（可配置）
- **实现策略**: 复用现有 Scheduler 16 个任务槽位，一个路灯模式曲线占用多个槽位

### 1.3 典型场景
```
场景: 路灯模式 - 黄昏渐暗
  18:00  100% ████████████████████  ← 控制点1
  20:00   60% ████████████         ← 控制点2 (中间线性渐变)
  22:00   30% ██████               ← 控制点3 (中间线性渐变)
  23:00   10% ██                   ← 控制点4 (中间线性渐变)
  06:00    0%                       ← 关灯(白天)
```

---

## 2. 设计方案

### 2.1 核心思路: 复用 Scheduler 槽位

路灯模式的一条曲线由 4-6 个控制点组成，每个控制点对应一个 Scheduler 任务。相邻控制点之间的平滑渐变通过固件端每分钟计算插值亮度来实现。

**关键设计**: 引入"路灯模式"标志，让固件识别哪些 Scheduler 槽位属于同一条渐变曲线，从而在控制点之间自动插值。

#### 槽位分配策略

| 槽位范围 | 用途 |
|---------|------|
| 0-7 | 路灯模式曲线（最多 8 个控制点，实际使用 4-6 个） |
| 8-15 | 普通定时任务（保留给用户其他定时需求） |

> **注意**: 若后续需要支持多条曲线（如不同星期不同曲线），可扩展为每条曲线 4 个槽位，支持 2 条曲线。

#### 数据编码

复用现有 10 字节 Scheduler Action Set 格式，通过扩展字段区分路灯模式:

| 字段 | 值 | 说明 |
|------|-----|------|
| **Action** | `0x03` (新增) | 标识此任务为路灯模式控制点 |
| **Brightness** | 0-100 | 此控制点的亮度 |
| **Repeat** | 0x7F (每天) | 路灯模式默认每天 |
| **Enabled** | 0/1 | 路灯模式整体启用/禁用 |

当 `Action == 0x03` 时，固件将该任务视为路灯模式控制点，与同组其他控制点协同工作。

### 2.2 渐变插值算法

固件端每分钟执行一次:

```
1. 获取当前时间 (hour:minute)
2. 遍历路灯模式控制点，按时间排序
3. 找到当前时间所在的区间 [点A, 点B]
4. 计算线性插值:
   progress = (当前分钟 - A.分钟) / (B.分钟 - A.分钟)
   brightness = A.brightness + progress * (B.brightness - A.brightness)
5. 设置灯光亮度
```

**边界处理**:
- 当前时间早于第一个控制点 → 使用第一个控制点的亮度
- 当前时间晚于最后一个控制点 → 使用最后一个控制点的亮度
- 只有一个控制点 → 固定亮度，无渐变
- 路灯模式禁用 → 不执行渐变，不影响普通定时任务

### 2.3 白天行为

路灯模式支持配置"白天模式":
- **关灯**: 白天 (如 06:00-18:00) 保持亮度 0%
- **固定亮度**: 白天保持某个亮度值（如 30%）

实现方式: 曲线的第一个控制点即为"白天结束时间"的亮度起点，最后一个控制点之后的亮度保持不变直到下一个曲线周期。白天关灯通过在曲线中设置一个亮度为 0% 的控制点（如 06:00 0%）来实现。

---

## 3. 固件实现规格

### 3.1 新增定义

```c
// 新增 Action 类型
#define SCHED_ACTION_STREETLIGHT  0x03  // 路灯模式控制点

// 路灯模式控制点最大数量
#define STREETLIGHT_MAX_POINTS    8

// 路灯模式状态
typedef struct {
    uint8_t  enabled;                           // 路灯模式是否启用
    uint8_t  point_count;                       // 控制点数量
    uint8_t  point_indices[STREETLIGHT_MAX_POINTS]; // 控制点对应的 Scheduler 索引
    uint8_t  last_brightness;                   // 上一次设置的亮度(避免重复设置)
} streetlight_state_t;
```

### 3.2 修改 scheduler_check_and_execute()

在现有的每分钟检查逻辑中增加路灯模式处理:

```c
void scheduler_check_and_execute(void) {
    uint8_t hour, min, sec;
    mesh_time_2_local(&g_time_state, &hour, &min, &sec);

    if (sec != 0) return;  // 只在每分钟第0秒检查

    // === 路灯模式渐变处理 ===
    if (streetlight_state.enabled && streetlight_state.point_count >= 2) {
        uint16_t current_min = hour * 60 + min;

        // 收集所有控制点的时间和亮度
        streetlight_point_t points[STREETLIGHT_MAX_POINTS];
        int count = 0;
        for (int i = 0; i < streetlight_state.point_count; i++) {
            scheduler_entry_t *e = &my_schedules[streetlight_state.point_indices[i]];
            if (e->action != SCHED_ACTION_STREETLIGHT || !e->enabled) continue;
            points[count].minutes = e->hour * 60 + e->minute;
            points[count].brightness = e->brightness;
            count++;
        }

        // 按时间排序
        sort_points_by_time(points, count);

        // 线性插值计算当前亮度
        uint8_t target = calculate_interpolated_brightness(points, count, current_min);

        if (target != streetlight_state.last_brightness) {
            gen_level_force_set_percent(target);
            streetlight_state.last_brightness = target;
        }
    }

    // === 普通定时任务处理 (现有逻辑) ===
    // ... 跳过 action == SCHED_ACTION_STREETLIGHT 的槽位
}
```

### 3.3 排序与插值

```c
// 按时间排序控制点(冒泡排序，数量少，足够)
static void sort_points_by_time(streetlight_point_t *points, int count) {
    for (int i = 0; i < count - 1; i++) {
        for (int j = 0; j < count - i - 1; j++) {
            if (points[j].minutes > points[j+1].minutes) {
                streetlight_point_t temp = points[j];
                points[j] = points[j+1];
                points[j+1] = temp;
            }
        }
    }
}

// 线性插值计算亮度
static uint8_t calculate_interpolated_brightness(
    streetlight_point_t *points, int count, uint16_t current_min)
{
    if (count == 0) return 0;
    if (count == 1) return points[0].brightness;

    // 当前时间早于第一个点 → 使用第一个点的亮度
    if (current_min <= points[0].minutes) return points[0].brightness;

    // 当前时间晚于最后一个点 → 使用最后一个点的亮度
    if (current_min >= points[count-1].minutes) return points[count-1].brightness;

    // 找到所在区间
    for (int i = 0; i < count - 1; i++) {
        if (current_min >= points[i].minutes && current_min < points[i+1].minutes) {
            uint16_t span = points[i+1].minutes - points[i].minutes;
            uint16_t elapsed = current_min - points[i].minutes;
            // 线性插值，四舍五入
            int16_t diff = points[i+1].brightness - points[i].brightness;
            uint8_t result = points[i].brightness + (uint8_t)((diff * elapsed + span/2) / span);
            return result;
        }
    }
    return points[count-1].brightness;
}
```

### 3.4 路灯模式启用/禁用

通过 Vendor Model 或自定义消息控制:

```
启用路灯模式:
  1. 设置 streetlight_state.enabled = 1
  2. 立即计算并应用当前亮度

禁用路灯模式:
  1. 设置 streetlight_state.enabled = 0
  2. 灯光保持当前亮度不变（或恢复到某个默认值）
```

### 3.5 控制点管理

当 App 下发一个 `Action == 0x03` 的 Scheduler Action Set 消息时:
1. 固件识别为路灯模式控制点
2. 将该槽位索引加入 `streetlight_state.point_indices`
3. 控制点数量 +1
4. 保存到 Flash

当 App 删除一个路灯模式控制点时:
1. 发送 `Action == NO_ACTION` 的 Scheduler Action Set
2. 固件从 `point_indices` 中移除该索引
3. 控制点数量 -1

---

## 4. Android App 实现规格

### 4.1 数据模型

```kotlin
/**
 * 路灯模式曲线配置
 */
data class StreetlightProfile(
    val deviceAddress: Int,       // 关联的设备 Mesh 地址
    val enabled: Boolean,         // 是否启用路灯模式
    val controlPoints: List<ControlPoint>  // 控制点列表(按时间排序)
) {
    data class ControlPoint(
        val hour: Int,            // 0-23
        val minute: Int,          // 0-59
        val brightness: Int       // 0-100
    )

    /**
     * 将控制点转换为 SchedulerTask 列表
     * 路灯模式占用槽位 0-7，每个控制点占用一个槽位
     */
    fun toSchedulerTasks(): List<SchedulerTask> {
        return controlPoints.mapIndexed { index, point ->
            SchedulerTask(
                index = index,           // 槽位 0-7
                hour = point.hour,
                minute = point.minute,
                action = SchedulerTask.Action.STREETLIGHT,  // 新增
                brightness = point.brightness,
                repeat = 0x7F,           // 每天
                enabled = this.enabled,
                deviceAddress = deviceAddress
            )
        }
    }

    companion object {
        /**
         * 从 SchedulerTask 列表中解析出路灯模式曲线
         */
        fun fromSchedulerTasks(tasks: List<SchedulerTask>, deviceAddress: Int): StreetlightProfile? {
            val streetlightTasks = tasks.filter { it.action == SchedulerTask.Action.STREETLIGHT }
            if (streetlightTasks.isEmpty()) return null

            val points = streetlightTasks.sortedBy { it.hour * 60 + it.minute }.map {
                ControlPoint(it.hour, it.minute, it.brightness)
            }
            return StreetlightProfile(
                deviceAddress = deviceAddress,
                enabled = streetlightTasks.all { it.enabled },
                controlPoints = points
            )
        }
    }
}
```

### 4.2 SchedulerTask.Action 扩展

```kotlin
enum class Action(val value: Int) {
    OFF(0x00),
    ON(0x01),
    STREETLIGHT(0x03),   // 新增: 路灯模式控制点
    NO_ACTION(0x0F);

    companion object {
        fun fromValue(value: Int): Action = when (value) {
            0x00 -> OFF
            0x01 -> ON
            0x03 -> STREETLIGHT
            0x0F -> NO_ACTION
            else -> OFF
        }
    }
}
```

### 4.3 UI 设计: 时间-亮度曲线编辑器

#### 整体布局

```
┌─────────────────────────────────────────────┐
│  ← 返回          路灯模式          保存      │
├─────────────────────────────────────────────┤
│                                             │
│  100% ┤ ╭─●─╮                               │
│       ┤ │    ╲                              │
│   80% ┤ │     ╲                             │
│       ┤ │      ╲                            │
│   60% ┤ │       ╲                           │
│       ┤ │        ●─╮                        │
│   40% ┤ │           ╲                       │
│       ┤ │            ╲                      │
│   20% ┤ │             ●                     │
│       ┤ │                                   │
│    0% ┤─╧─●─────────────────────────────── │
│       └──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──→   │
│         0  2  4  6  8  10 12 14 16 18 20 22 24 │
│         时间(时)                               │
│                                             │
├─────────────────────────────────────────────┤
│  ● 18:00  100%  [×删除]                     │
│  ● 20:00   60%  [×删除]                     │
│  ● 22:00   30%  [×删除]                     │
│  ● 23:00   10%  [×删除]                     │
│  ● 06:00    0%  [×删除]                     │
│                              [+ 添加控制点]   │
├─────────────────────────────────────────────┤
│  [启用路灯模式  ●━━━━━○]                     │
│  [下发到设备]                                │
└─────────────────────────────────────────────┘
```

#### 自定义 View: StreetlightCurveView

核心交互组件，继承自 View:

**绘制内容**:
- 背景网格线（时间刻度 + 亮度刻度）
- 折线连接所有控制点
- 控制点圆圈（可拖动）
- 控制点旁显示时间和亮度标签
- 曲线下方区域填充半透明颜色

**触摸交互**:
- **拖动控制点**: 同时改变时间和亮度
  - X 方向: 映射到 0:00-24:00，吸附到 5 分钟刻度
  - Y 方向: 映射到 0%-100%，吸附到 5% 刻度
- **点击空白区域**: 添加新控制点（最多 6 个）
- **长按控制点**: 删除该控制点（最少 2 个）
- **拖动约束**: 控制点时间不能交叉（保持时间排序）

**实现要点**:
```kotlin
class StreetlightCurveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var controlPoints = mutableListOf<ControlPoint>()
        set(value) {
            field = value
            invalidate()
        }

    // 触摸相关
    private var draggedPointIndex = -1
    private val pointRadius = 24f  // dp
    private val snapMinutes = 5    // 时间吸附粒度
    private val snapBrightness = 5 // 亮度吸附粒度

    // 坐标映射
    private fun timeToX(hour: Int, minute: Int): Float { ... }
    private fun brightnessToY(brightness: Int): Float { ... }
    private fun xToTime(x: Float): Pair<Int, Int> { ... }
    private fun yToBrightness(y: Float): Int { ... }

    override fun onDraw(canvas: Canvas) {
        drawGrid(canvas)
        drawCurve(canvas)
        drawControlPoints(canvas)
        drawLabels(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                draggedPointIndex = findPointNear(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggedPointIndex >= 0) {
                    val (h, m) = xToTime(event.x)
                    val b = yToBrightness(event.y)
                    // 约束: 不与相邻控制点时间重叠
                    controlPoints[draggedPointIndex] = ControlPoint(h, m, b)
                    onPointChanged?.invoke(draggedPointIndex, controlPoints[draggedPointIndex])
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                draggedPointIndex = -1
            }
        }
        return true
    }
}
```

### 4.4 Activity: StreetlightModeActivity

```kotlin
class StreetlightModeActivity : ComponentActivity() {

    private val viewModel: MeshViewModel by viewModels()
    private var deviceAddress: Int = 0
    private var profile = StreetlightProfile(
        deviceAddress = 0,
        enabled = false,
        controlPoints = listOf(
            ControlPoint(18, 0, 100),  // 默认: 18:00 100%
            ControlPoint(22, 0, 30),   // 22:00 30%
            ControlPoint(23, 0, 10),   // 23:00 10%
            ControlPoint(6, 0, 0),     // 06:00 关灯
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. 加载本地缓存的配置
        // 2. 设置曲线编辑器
        // 3. 保存按钮: 转换为 SchedulerTask 列表，逐个下发到设备
        // 4. 观察下发结果
    }

    private fun saveToFlash() {
        // 保存到 SharedPreferences / Room
    }

    private fun sendToDevice() {
        val tasks = profile.toSchedulerTasks()
        // 先清除旧的路灯模式槽位 (0-7)
        for (i in 0..7) {
            viewModel.setSchedulerTask(deviceAddress, SchedulerTask(
                index = i,
                action = Action.NO_ACTION,
                ...
            ))
        }
        // 逐个下发新的控制点
        tasks.forEach { task ->
            viewModel.setSchedulerTask(deviceAddress, task)
        }
    }
}
```

### 4.5 入口按钮

在 `DeviceDetailActivity` 的"定时任务"卡片中添加"路灯模式"按钮:

```xml
<Button
    android:id="@+id/btnStreetlightMode"
    android:layout_width="match_parent"
    android:layout_height="36dp"
    android:text="路灯模式"
    android:textSize="13sp"
    android:backgroundTint="#FF9800"
    android:textColor="@android:color/white"
    android:layout_marginTop="8dp"/>
```

---

## 5. 槽位冲突处理

### 5.1 槽位分配规则

| 槽位 | 用途 | 说明 |
|------|------|------|
| 0-7 | 路灯模式控制点 | 最多 8 个控制点，实际使用 4-6 个 |
| 8-15 | 普通定时任务 | 用户手动添加的开关灯任务 |

### 5.2 互斥规则

- 启用路灯模式时，槽位 0-7 会被覆盖为路灯模式控制点
- 路灯模式启用期间，槽位 0-7 不可用于普通定时任务
- 禁用路灯模式时，释放槽位 0-7，可恢复为普通定时任务
- 槽位 8-15 不受路灯模式影响

### 5.3 SchedulerListActivity 适配

- 列表中区分显示路灯模式控制点和普通定时任务
- 路灯模式控制点显示为不可单独编辑（引导用户进入路灯模式编辑器）
- 普通定时任务只能使用槽位 8-15

---

## 6. 消息协议

### 6.1 路灯模式启用/禁用

复用现有 Scheduler Action Set 消息:

**启用路灯模式**:
- 设置所有控制点的 `enabled = 1`
- 每个控制点: `Action = 0x03`, `Brightness = XX`, `Repeat = 0x7F`

**禁用路灯模式**:
- 设置所有控制点的 `enabled = 0`
- 或将控制点替换为 `Action = NO_ACTION`

### 6.2 控制点设置

与普通 Scheduler Action Set 相同 (OpCode: 0x60)，仅 `Action = 0x03` 区分:

```
Byte 0: Index (0-7)
Byte 1-2: Year (0x0000 = 任意年份)
Byte 3: Month(0x00) + Hour低4位
Byte 4: Hour高位 + Minute + Second低位
Byte 5: Second高5位 + Repeat低3位
Byte 6: Repeat高4位 + Action (0x03)
Byte 7: Brightness
Byte 8: Enabled
Byte 9: 保留
```

---

## 7. 实现计划

### Phase 1: 固件端改造

#### Task 1.1: 新增路灯模式定义与状态
- 在 `app_scheduler_model.h` 添加 `SCHED_ACTION_STREETLIGHT 0x03`
- 添加 `streetlight_state_t` 结构体和全局变量
- 修改 `Action.fromValue` 解析支持 0x03

#### Task 1.2: 修改 scheduler_check_and_execute()
- 在每分钟检查中增加路灯模式渐变处理
- 收集所有 `Action == 0x03` 的控制点
- 按时间排序
- 线性插值计算当前亮度
- 设置灯光亮度
- 跳过路灯模式槽位，不执行普通定时任务逻辑

#### Task 1.3: 路灯模式状态管理
- 下发控制点时自动维护 `streetlight_state`
- 支持启用/禁用
- Flash 持久化路灯模式状态

#### Task 1.4: 调试与测试
- 串口打印渐变过程
- 验证插值正确性
- 验证边界条件

### Phase 2: App 端实现

#### Task 2.1: 数据模型扩展
- `SchedulerTask.Action` 添加 `STREETLIGHT(0x03)`
- 创建 `StreetlightProfile` 数据类
- 创建 `StreetlightRepository` 本地存储

#### Task 2.2: 曲线编辑器 View
- 实现 `StreetlightCurveView` 自定义 View
- 绘制: 网格、折线、控制点、标签
- 交互: 拖动、添加、删除控制点
- 吸附: 时间 5 分钟粒度，亮度 5% 粒度
- 约束: 时间不交叉，控制点 2-6 个

#### Task 2.3: 路灯模式 Activity
- 创建 `StreetlightModeActivity`
- 集成曲线编辑器
- 控制点列表同步显示
- 保存/下发按钮
- 启用/禁用开关

#### Task 2.4: 入口与集成
- `DeviceDetailActivity` 添加"路灯模式"按钮
- `SchedulerListActivity` 区分路灯模式控制点
- `SchedulerMessageHelper` 支持编码 Action=0x03

### Phase 3: 联调测试

#### Task 3.1: 功能测试
- 设置 4 个控制点，验证渐变曲线
- 启用/禁用路灯模式
- 断电重启后恢复

#### Task 3.2: 边界测试
- 跨午夜场景 (如 22:00 → 次日 06:00)
- 所有控制点相同亮度
- 只有一个控制点

#### Task 3.3: 与普通定时任务共存测试
- 路灯模式 + 普通定时任务同时存在
- 禁用路灯模式后普通任务正常

---

## 8. 跨午夜处理

路灯模式的曲线可能跨越午夜（如 22:00 渐暗 → 06:00 关灯）。

**处理方式**: 将时间轴视为环形。当最后一个控制点的时间早于第一个控制点（或当前时间已过最后一个控制点但未到第一个控制点），则当前处于"跨午夜区间"。

```
示例: 控制点 22:00(30%) → 23:00(10%) → 06:00(0%) → 18:00(100%)
排序后: 06:00(0%) → 18:00(100%) → 22:00(30%) → 23:00(10%)

凌晨 02:00 时:
  位于 23:00 和 06:00 之间(跨午夜)
  progress = (2*60 - 23*60 + 24*60) / (6*60 - 23*60 + 24*60) = 180/420
  brightness = 10 + 180/420 * (0 - 10) ≈ 5.7% ≈ 6%
```

固件端插值算法需要处理分钟数回绕的情况:
```c
// 跨午夜计算: 如果 end < start，加 24*60 调整
int16_t span = end_minutes - start_minutes;
if (span < 0) span += 24 * 60;

int16_t elapsed = current_minutes - start_minutes;
if (elapsed < 0) elapsed += 24 * 60;
```

---

## 9. 约束与风险

| 约束/风险 | 说明 | 缓解措施 |
|-----------|------|----------|
| 槽位数量 | 路灯模式占用 0-7 号槽位，普通任务只剩 8-15 | 8 个普通任务对大多数场景够用 |
| 渐变粒度 | 每分钟调整一次亮度，对 Flash 无额外写入 | 仅修改运行时亮度，不持久化每步 |
| Action 值 | 0x03 非 Mesh 标准值，第三方工具可能不识别 | 仅在自研 App+固件间使用 |
| 跨午夜 | 折线图和插值需正确处理 24 时回绕 | 算法中统一处理 |
| 曲线编辑器 | 自定义 View 开发工作量较大 | 可先用简单版（SeekBar + 列表），后续升级为曲线图 |

---

**文档结束**
