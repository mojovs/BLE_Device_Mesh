# Scheduler Model 调试指南

## 问题现象
点击"获取计划"按钮后，固件没有串口输出，App 5秒后超时。

## 原因分析

### 1. 固件未实现 Scheduler Server Model

Scheduler Model 是 Bluetooth Mesh 规范中的可选模型，固件必须显式实现。

#### 检查固件是否注册了 Scheduler Server Model

```c
// 固件中应该有类似的代码
static mesh_model_info_t scheduler_server_model = {
    .model_id = SCHEDULER_SERVER_MODEL_ID,  // 0x1206
    .element_index = 0,
    .pub = NULL,
    .data = NULL
};

// 在初始化时注册
mesh_element_register_model(&scheduler_server_model);
```

**如果固件中没有这段代码，说明固件根本不支持 Scheduler 功能。**

### 2. Scheduler Model 未绑定 AppKey

即使固件实现了 Scheduler Server，如果没有绑定 AppKey，也无法解密 App 发送的消息。

```c
// 在配网完成后绑定 AppKey
void on_provision_complete(uint16_t unicast_addr, uint8_t *appkey, uint8_t appkey_index) {
    // 绑定 AppKey 到 Scheduler Server Model
    mesh_model_bind_appkey(SCHEDULER_SERVER_MODEL_ID, 0, appkey_index);
    printf("Scheduler Server Model 已绑定 AppKey[%d]\n", appkey_index);
}
```

### 3. 消息处理函数未实现

固件需要实现 Scheduler 消息的处理逻辑：

```c
void scheduler_server_msg_handler(mesh_model_info_t *model_info, mesh_msg_t *msg) {
    printf("Scheduler Server 收到消息: opcode=0x%04X, src=0x%04X, dst=0x%04X, len=%d\n",
           msg->opcode, msg->src, msg->dst, msg->len);

    switch(msg->opcode) {
        case SCHEDULER_GET:  // 0x8249
            printf("收到 SCHEDULER_GET 请求，准备发送回复\n");
            send_scheduler_status(msg->src);
            break;

        case SCHEDULER_ACTION_GET:  // 0x8248
            printf("收到 SCHEDULER_ACTION_GET 请求\n");
            uint8_t index = msg->data[0];
            send_scheduler_action_status(msg->src, index);
            break;

        case SCHEDULER_ACTION_SET:  // 0x60
        case SCHEDULER_ACTION_SET_UNACKNOWLEDGED:  // 0x61
            printf("收到 SCHEDULER_ACTION_SET 请求\n");
            parse_and_set_scheduler_action(msg->data, msg->len);
            if (msg->opcode == SCHEDULER_ACTION_SET) {
                send_scheduler_action_status(msg->src, msg->data[0]);
            }
            break;

        default:
            printf("未知 Scheduler opcode: 0x%04X\n", msg->opcode);
            break;
    }
}

// 注册消息处理函数
scheduler_server_model.msg_handler = scheduler_server_msg_handler;
```

### 4. 没有添加调试日志

即使固件实现了 Scheduler Server，如果没有添加串口日志，你也看不到任何输出。

**最基本的调试日志：**

```c
// 在 Network Layer 接收函数中添加
void mesh_network_layer_receive(uint8_t *data, uint8_t len) {
    printf("收到 Mesh 消息: len=%d\n", len);
    // 继续处理...
}

// 在 Transport Layer 解密成功后添加
void mesh_transport_layer_receive(uint16_t src, uint16_t dst, uint8_t *payload, uint8_t len) {
    printf("解密成功！Src: 0x%04X, Dst: 0x%04X\n", src, dst);
}

// 在 Access Layer 识别 Opcode 后添加
void mesh_access_layer_receive(uint16_t src, uint16_t dst, uint32_t opcode, uint8_t *params, uint8_t len) {
    printf("Access Layer: Opcode=0x%X\n", opcode);
    
    if (opcode == 0x8249) {
        printf("识别到 SchedulerGet 请求！\n");
    }
}
```

## 调试步骤

### 第一步：确认固件是否收到任何 Mesh 消息

在固件的最底层（Network Layer）添加日志：

```c
void mesh_network_layer_receive(uint8_t *data, uint8_t len) {
    printf("RX: ");
    for (int i = 0; i < len; i++) {
        printf("%02X ", data[i]);
    }
    printf("\n");
}
```

**测试：**
1. 点击 App 的"获取计划"按钮
2. 观察串口是否有 `RX: XX XX XX ...` 输出

**结果判断：**
- ✅ **有输出** → 消息到达了固件，继续第二步
- ❌ **无输出** → 消息根本没到达固件，检查：
  - BLE 连接是否正常
  - Mesh 网络是否已建立
  - 设备地址是否正确

### 第二步：确认消息是否解密成功

在 Transport Layer 添加日志：

```c
void mesh_transport_layer_receive(uint16_t src, uint16_t dst, uint8_t *payload, uint8_t len) {
    printf("解密成功！Src: 0x%04X, Dst: 0x%04X, Len: %d\n", src, dst, len);
}

void mesh_transport_layer_decrypt_failed(uint16_t src) {
    printf("解密失败！Src: 0x%04X\n", src);
    printf("可能原因：\n");
    printf("  1. Network Key 不匹配\n");
    printf("  2. AppKey 未绑定\n");
    printf("  3. IV Index 不同步\n");
}
```

**结果判断：**
- ✅ **解密成功** → 继续第三步
- ❌ **解密失败** → 检查：
  - AppKey 是否已绑定到 Scheduler Model
  - Network Key 是否正确
  - IV Index 是否同步

### 第三步：确认 Opcode 是否正确识别

在 Access Layer 添加日志：

```c
void mesh_access_layer_receive(uint16_t src, uint16_t dst, uint32_t opcode, uint8_t *params, uint8_t len) {
    printf("Opcode: 0x%X, Len: %d\n", opcode, len);
    
    // SchedulerGet 的 Opcode 是 0x8249
    if (opcode == 0x8249) {
        printf("✓ 识别到 SchedulerGet 请求！\n");
    } else {
        printf("✗ 未知 Opcode\n");
    }
}
```

**结果判断：**
- ✅ **识别到 0x8249** → 继续第四步
- ❌ **Opcode 不是 0x8249** → 检查：
  - App 发送的 Opcode 是否正确
  - 固件的 Opcode 定义是否正确

### 第四步：确认消息是否路由到 Scheduler Model

在 Model 分发函数中添加日志：

```c
void mesh_model_dispatch(uint16_t model_id, uint32_t opcode, uint8_t *params, uint8_t len) {
    printf("分发到 Model: 0x%04X, Opcode: 0x%X\n", model_id, opcode);
    
    if (model_id == SCHEDULER_SERVER_MODEL_ID) {
        printf("✓ 路由到 Scheduler Server Model\n");
    } else {
        printf("✗ 未找到对应的 Model\n");
    }
}
```

**结果判断：**
- ✅ **路由到 Scheduler Model** → 继续第五步
- ❌ **未路由** → 检查：
  - Scheduler Server Model 是否已注册
  - Model ID 是否正确（应该是 0x1206）

### 第五步：确认消息处理函数是否被调用

在 Scheduler 消息处理函数中添加日志：

```c
void scheduler_server_msg_handler(mesh_model_info_t *model_info, mesh_msg_t *msg) {
    printf("✓ Scheduler 消息处理函数被调用！\n");
    printf("  Opcode: 0x%04X\n", msg->opcode);
    printf("  Src: 0x%04X\n", msg->src);
    printf("  Dst: 0x%04X\n", msg->dst);
    printf("  Len: %d\n", msg->len);
    
    // 处理消息...
}
```

**结果判断：**
- ✅ **函数被调用** → 继续第六步
- ❌ **函数未被调用** → 检查：
  - 消息处理函数是否已注册
  - 函数指针是否正确

### 第六步：确认回复是否发送

在发送 SchedulerStatus 时添加日志：

```c
void send_scheduler_status(uint16_t dst) {
    printf("准备发送 SchedulerStatus 到 0x%04X\n", dst);
    
    // 构造 SchedulerStatus 消息
    uint8_t status[2];
    uint16_t schedules = get_scheduler_bitmap();  // 获取已设置的计划位图
    
    status[0] = schedules & 0xFF;
    status[1] = (schedules >> 8) & 0xFF;
    
    printf("Schedules 位图: 0x%04X\n", schedules);
    
    // 发送消息
    int ret = mesh_model_send(
        element_addr,                // 源地址
        dst,                         // 目标地址
        SCHEDULER_STATUS_OPCODE,     // 0x824A
        status,
        sizeof(status),
        appkey_index
    );
    
    printf("mesh_model_send 返回: %d\n", ret);
    
    if (ret != 0) {
        printf("✗ 发送失败！错误码: %d\n", ret);
    } else {
        printf("✓ 发送成功\n");
    }
}
```

## Scheduler Model 消息格式

### SchedulerGet (0x8249)
- **方向**: App → 固件
- **参数**: 无
- **回复**: SchedulerStatus

### SchedulerStatus (0x824A)
- **方向**: 固件 → App
- **参数**: 2 字节
  - `schedules[0]`: 低 8 位（索引 0-7）
  - `schedules[1]`: 高 8 位（索引 8-15）
- **说明**: 每个 bit 表示对应索引的计划是否已设置
  - bit 0 = 1: 索引 0 已设置
  - bit 1 = 1: 索引 1 已设置
  - ...
  - bit 15 = 1: 索引 15 已设置

**示例：**
```
schedules = 0x0003  // 二进制: 0000 0000 0000 0011
表示索引 0 和 1 已设置计划
```

### SchedulerActionGet (0x8248)
- **方向**: App → 固件
- **参数**: 1 字节
  - `index`: 计划索引 (0-15)
- **回复**: SchedulerActionStatus

### SchedulerActionStatus (0x5F)
- **方向**: 固件 → App
- **参数**: 10 字节
  - `index`: 计划索引 (0-15)
  - `year`: 年份 (0x64 = 2000 + 100 = 2100)
  - `month`: 月份 (1-12, 0 = 任意月)
  - `day`: 日期 (1-31, 0 = 任意日)
  - `hour`: 小时 (0-23, 0x18 = 任意小时)
  - `minute`: 分钟 (0-59, 0x3C = 任意分钟)
  - `second`: 秒 (0-59, 0x3C = 任意秒)
  - `dayOfWeek`: 星期 (bit 0-6 对应周一到周日, 0x7F = 每天)
  - `action`: 动作 (0 = 关, 1 = 开, 2 = 场景回调)
  - `transitionTime`: 过渡时间
  - `sceneNumber`: 场景编号 (仅当 action = 2 时有效)

## 常见错误

### 错误 1：固件未实现 Scheduler Server Model
**症状**: 固件完全没有任何日志输出

**解决方案**: 
1. 检查固件代码中是否有 `SCHEDULER_SERVER_MODEL_ID` 的定义
2. 检查是否调用了 `mesh_element_register_model(&scheduler_server_model)`
3. 如果没有，说明固件不支持 Scheduler 功能，需要添加实现

### 错误 2：AppKey 未绑定
**症状**: 固件收到消息但解密失败

**解决方案**:
```c
// 在配网完成后绑定 AppKey
mesh_model_bind_appkey(SCHEDULER_SERVER_MODEL_ID, 0, appkey_index);
```

### 错误 3：Opcode 不匹配
**症状**: 固件收到消息但无法识别

**解决方案**: 检查 Opcode 定义
```c
#define SCHEDULER_GET                           0x8249  // 2 字节
#define SCHEDULER_STATUS                        0x824A  // 2 字节
#define SCHEDULER_ACTION_GET                    0x8248  // 2 字节
#define SCHEDULER_ACTION_STATUS                 0x5F    // 1 字节
#define SCHEDULER_ACTION_SET                    0x60    // 1 字节
#define SCHEDULER_ACTION_SET_UNACKNOWLEDGED     0x61    // 1 字节
```

### 错误 4：发送缓冲区已满
**症状**: 固件收到消息并尝试回复，但发送失败

**解决方案**:
```c
// 在发送前检查缓冲区
if (!mesh_tx_buffer_available()) {
    printf("发送缓冲区已满，延迟发送\n");
    // 延迟重试或清理缓冲区
    return;
}
```

## App 端日志分析

当点击"获取计划"按钮时，App 的 Logcat 应该显示：

```
D/DeviceDetail: 读取计划按钮被点击
D/DeviceDetail: 设备已连接，开始读取计划
D/MeshApp: 发送 SchedulerGet 到地址: 0x99
D/MeshApp: 正在读取调度状态...
```

如果 5 秒后看到：
```
W/MeshApp: SchedulerGet 超时，可能原因：1) 固件未实现 Scheduler Server 2) Model 未绑定 3) OpCode 不匹配
```

说明固件没有发送回复。

如果收到回复，会看到：
```
D/MeshApp: 收到 SchedulerStatus (Src: 0x99)
D/MeshApp: 解析到 Scheduler 状态: 0x0003 (Src: 0x99)
D/MeshApp: 已设置的调度索引: 0, 1
D/MeshApp: 开始读取 2 个计划的详细信息
```

## 快速诊断命令

如果你有固件源代码访问权限，可以添加一个测试命令：

```c
// 串口命令：test_scheduler
void cmd_test_scheduler(void) {
    printf("=== Scheduler Model 状态 ===\n");
    printf("Model ID: 0x%04X\n", SCHEDULER_SERVER_MODEL_ID);
    printf("是否已注册: %s\n", is_model_registered(SCHEDULER_SERVER_MODEL_ID) ? "是" : "否");
    printf("是否已绑定 AppKey: %s\n", is_model_bound(SCHEDULER_SERVER_MODEL_ID) ? "是" : "否");
    printf("消息处理函数: %p\n", scheduler_server_model.msg_handler);
    printf("已设置的计划: 0x%04X\n", get_scheduler_bitmap());
}
```

## 总结

**最可能的原因（按概率排序）：**

1. **固件未实现 Scheduler Server Model** (90%)
   - 大多数 BLE Mesh 固件只实现基本的 Generic OnOff Model
   - Scheduler Model 是可选的，需要额外实现

2. **固件实现了但未绑定 AppKey** (5%)
   - 固件有 Scheduler Model 但忘记绑定 AppKey

3. **固件实现了但没有日志输出** (3%)
   - 固件正常工作但没有串口日志

4. **其他原因** (2%)
   - Opcode 不匹配、发送缓冲区满等

**建议的检查顺序：**

1. 先检查固件代码中是否有 `SCHEDULER_SERVER_MODEL_ID` 或 `0x1206` 的定义
2. 如果没有，说明固件不支持 Scheduler 功能
3. 如果有，按照上面的调试步骤逐步排查
