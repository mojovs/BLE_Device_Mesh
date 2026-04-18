# 固件端 Time Server 调试指南

## 问题现象
App 发送 TimeGet 请求后，5秒超时，显示"设备响应超时 - 固件可能未实现 Time Server"

## 可能的原因

### 1. Time Server Model 未正确注册
检查固件中是否正确注册了 Time Server Model：

```c
// 确保在 element 中添加了 Time Server Model
static mesh_model_info_t time_server_model = {
    .model_id = TIME_SERVER_MODEL_ID,  // 0x1200
    .element_index = 0,
    .pub = NULL,
    .data = NULL
};

// 在初始化时注册
mesh_element_register_model(&time_server_model);
```

### 2. AppKey 未绑定到 Time Server Model
固件必须将 AppKey 绑定到 Time Server Model 才能解密消息：

```c
// 在配网完成后绑定 AppKey
void on_provision_complete(uint16_t unicast_addr, uint8_t *appkey, uint8_t appkey_index) {
    // 绑定 AppKey 到 Time Server Model
    mesh_model_bind_appkey(TIME_SERVER_MODEL_ID, 0, appkey_index);
    printf("Time Server Model 已绑定 AppKey[%d]\n", appkey_index);
}
```

### 3. 消息处理函数未实现或未注册
确保 Time Server 的消息处理函数已注册：

```c
void time_server_msg_handler(mesh_model_info_t *model_info, mesh_msg_t *msg) {
    printf("Time Server 收到消息: opcode=0x%04X, src=0x%04X, dst=0x%04X, len=%d\n",
           msg->opcode, msg->src, msg->dst, msg->len);

    switch(msg->opcode) {
        case TIME_GET:  // 0x8237
            printf("收到 TIME_GET 请求，准备发送回复\n");
            send_time_status(msg->src);
            break;

        case TIME_SET:  // 0x5C
            printf("收到 TIME_SET 请求\n");
            parse_and_set_time(msg->data, msg->len);
            // TimeSet 不需要回复
            break;

        default:
            printf("未知 Time opcode: 0x%04X\n", msg->opcode);
            break;
    }
}

// 注册消息处理函数
time_server_model.msg_handler = time_server_msg_handler;
```

### 4. Time Status 回复未发送或格式错误

#### 检查发送缓冲区
```c
void send_time_status(uint16_t dst) {
    printf("准备发送 Time Status 到 0x%04X\n", dst);

    // 检查发送缓冲区
    if (!mesh_tx_buffer_available()) {
        printf("错误：发送缓冲区已满！\n");
        return;
    }

    // 构造 Time Status 消息
    uint8_t status[10];
    uint32_t tai_seconds = get_current_tai_time();

    // Time Status 格式 (Opcode: 0x5D)
    status[0] = tai_seconds & 0xFF;
    status[1] = (tai_seconds >> 8) & 0xFF;
    status[2] = (tai_seconds >> 16) & 0xFF;
    status[3] = (tai_seconds >> 24) & 0xFF;
    status[4] = (tai_seconds >> 32) & 0xFF;
    status[5] = 0;  // SubSecond
    status[6] = 0;  // Uncertainty
    status[7] = 0;  // Time Authority
    status[8] = 37; // TAI-UTC Delta (2024年)
    status[9] = 32; // Time Zone Offset (UTC+8 = 8*4 = 32)

    printf("Time Status 数据: ");
    for (int i = 0; i < 10; i++) {
        printf("%02X ", status[i]);
    }
    printf("\n");

    // 发送消息
    int ret = mesh_model_send(
        element_addr,           // 源地址（本设备的 Unicast 地址）
        dst,                    // 目标地址（请求者的地址）
        TIME_STATUS_OPCODE,     // 0x5D
        status,
        sizeof(status),
        appkey_index
    );

    printf("mesh_model_send 返回: %d\n", ret);

    if (ret != 0) {
        printf("发送失败！错误码: %d\n", ret);
    }
}
```

#### 检查 TAI 时间计算
```c
uint64_t get_current_tai_time() {
    // 获取系统时间（Unix 时间戳）
    uint32_t unix_time = get_system_time();

    // TAI = Unix + 37 秒（2024年的 TAI-UTC 差值）
    uint64_t tai_time = (uint64_t)unix_time + 37;

    printf("当前时间: Unix=%u, TAI=%llu\n", unix_time, tai_time);

    return tai_time;
}
```

### 5. Network Layer 配置问题

#### 检查 Network Key 和 IV Index
```c
void on_mesh_message_decrypt_failed(uint16_t src, uint8_t *encrypted_data, uint8_t len) {
    printf("消息解密失败！Src: 0x%04X\n", src);
    printf("可能原因：\n");
    printf("  1. Network Key 不匹配\n");
    printf("  2. IV Index 不同步\n");
    printf("  3. Sequence Number 错误\n");

    // 打印当前配置
    printf("当前 Network Key Index: %d\n", current_netkey_index);
    printf("当前 IV Index: 0x%08X\n", current_iv_index);
}
```

## 调试步骤

### 第一步：确认消息是否到达固件
在固件的 Network Layer 接收函数中添加日志：

```c
void mesh_network_layer_receive(uint8_t *data, uint8_t len) {
    printf("收到 Mesh 消息: len=%d, data=", len);
    for (int i = 0; i < len; i++) {
        printf("%02X ", data[i]);
    }
    printf("\n");

    // 继续处理...
}
```

### 第二步：确认消息是否解密成功
```c
void mesh_transport_layer_receive(uint16_t src, uint16_t dst, uint8_t *payload, uint8_t len) {
    printf("解密成功！Src: 0x%04X, Dst: 0x%04X, Payload: ", src, dst);
    for (int i = 0; i < len; i++) {
        printf("%02X ", payload[i]);
    }
    printf("\n");
}
```

### 第三步：确认 Opcode 是否正确识别
```c
void mesh_access_layer_receive(uint16_t src, uint16_t dst, uint32_t opcode, uint8_t *params, uint8_t len) {
    printf("Access Layer: Src=0x%04X, Dst=0x%04X, Opcode=0x%X, Len=%d\n",
           src, dst, opcode, len);

    // TimeGet 的 Opcode 是 0x8237
    if (opcode == 0x8237) {
        printf("识别到 TimeGet 请求！\n");
    }
}
```

### 第四步：确认回复是否发送
在发送函数中添加详细日志（见上面的 send_time_status 示例）

## App 端日志分析

当 App 发送 TimeGet 时，你应该在 Logcat 中看到：

```
D/MeshApp: 发送 TimeGet 到地址: 0x99, AppKey Index: 0
D/MeshApp: 正在读取设备时间...
```

如果 5 秒后看到：
```
W/MeshApp: TimeGet 超时，固件可能：1) 未绑定 AppKey 2) 发送缓冲区满 3) 未实现回复逻辑
```

说明固件没有发送回复。

如果收到回复，会看到：
```
D/MeshApp: 收到 TimeStatus (Src: 0x99)
D/MeshApp: TimeStatus 原始数据: XX XX XX XX XX XX XX XX XX XX
D/MeshApp: 收到 TimeStatus 原始数据:
D/MeshApp:   - taiSeconds: 1234567890
D/MeshApp:   - subSecond: 0
D/MeshApp:   - uncertainty: 0
D/MeshApp: 解析到设备时间: TAI=1234567890, Unix=1234567853 (Src: 0x99)
```

## 常见错误

### 错误 1：Opcode 不匹配
- TimeGet Opcode: `0x8237` (2字节)
- TimeSet Opcode: `0x5C` (1字节)
- TimeStatus Opcode: `0x5D` (1字节)

### 错误 2：消息长度错误
- TimeGet: 0 字节参数
- TimeSet: 10 字节参数
- TimeStatus: 10 字节参数

### 错误 3：字节序错误
TAI 时间使用小端序（Little Endian）：
```c
// 正确
status[0] = tai_seconds & 0xFF;
status[1] = (tai_seconds >> 8) & 0xFF;
// ...

// 错误（大端序）
status[0] = (tai_seconds >> 24) & 0xFF;
status[1] = (tai_seconds >> 16) & 0xFF;
```

## 测试建议

1. **先测试 TimeSet**（不需要回复）
   - 在固件中添加日志，确认收到 TimeSet 消息
   - 确认时间是否正确设置

2. **再测试 TimeGet**（需要回复）
   - 确认收到 TimeGet 消息
   - 确认发送 TimeStatus 回复
   - 确认 App 收到回复

3. **使用 nRF Mesh App 对比测试**
   - 用官方 App 发送 TimeGet，看固件是否响应
   - 如果官方 App 也超时，说明是固件问题
   - 如果官方 App 正常，说明是我们 App 的问题
