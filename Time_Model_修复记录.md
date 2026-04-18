# Time Model 修复记录

## 问题描述

固件编译时出现链接错误：
```
undefined reference to `net_buf_simple_reset'
```

同时 `bt_mesh_model_send()` 返回 -1，导致 Time Status 消息发送失败。

## 根本原因

1. **`net_buf_simple_reset()` 函数不存在**
   - CH592 的 MESH_LIB 中没有提供此函数
   - 代码中调用了不存在的 API

2. **缓冲区使用方式错误**
   - Time Model 使用 `model->pub->msg` 指针
   - 其他成功的 Model（Generic Level、Sensor）使用栈上缓冲区
   - 缺少 TTL 设置

## 解决方案

### 修改 1：删除 `net_buf_simple_reset()` 调用

**原代码：**
```c
// 重置缓冲区
net_buf_simple_reset(msg);

// 初始化消息
bt_mesh_model_msg_init(msg, BLE_MESH_MODEL_OP_TIME_STATUS);
```

**修改后：**
```c
// 初始化消息（会自动重置缓冲区）
bt_mesh_model_msg_init(msg, BLE_MESH_MODEL_OP_TIME_STATUS);
```

**原因：** `bt_mesh_model_msg_init()` 已经包含了缓冲区初始化功能。

### 修改 2：改用栈上缓冲区并设置 TTL

**原代码：**
```c
void time_status_send(struct bt_mesh_model *model, struct bt_mesh_msg_ctx *ctx)
{
    // 大量调试日志和检查...

    // 使用 model->pub->msg 作为发送缓冲区
    struct net_buf_simple *msg = model->pub->msg;

    bt_mesh_model_msg_init(msg, BLE_MESH_MODEL_OP_TIME_STATUS);

    // 构建消息...

    // 发送（缺少 TTL 设置）
    int ret = bt_mesh_model_send(model, ctx, msg, NULL, NULL);
}
```

**修改后（参考 Generic Level Model）：**
```c
void time_status_send(struct bt_mesh_model *model, struct bt_mesh_msg_ctx *ctx)
{
    NET_BUF_SIMPLE_DEFINE(msg, 32);  // 栈上定义缓冲区
    int err;

    APP_DBG("time_status_send> 发送 Time Status 到 0x%04x", ctx->addr);

    // 初始化消息
    bt_mesh_model_msg_init(&msg, BLE_MESH_MODEL_OP_TIME_STATUS);

    // 1. TAI Seconds (5 字节)
    uint64_t tai_sec = g_time_state.tai_seconds;
    net_buf_simple_add_le32(&msg, (uint32_t)(tai_sec & 0xFFFFFFFF));
    net_buf_simple_add_u8(&msg, (uint8_t)((tai_sec >> 32) & 0xFF));

    // 2. Sub-second (1 字节)
    net_buf_simple_add_u8(&msg, g_time_state.subsecond);

    // 3. Uncertainty (1 字节)
    net_buf_simple_add_u8(&msg, g_time_state.uncertainty);

    // 4. Time Authority (1 bit) + TAI-UTC Delta (15 bits)
    uint16_t auth_delta = (g_time_state.time_authority & 0x01 << 15) | (g_time_state.tai_utc_delta & 0x7FFF);
    net_buf_simple_add_le16(&msg, auth_delta);

    // 5. Timezone Offset (1 字节)
    net_buf_simple_add_u8(&msg, g_time_state.timezone_offset);

    APP_DBG("time_status_send> TAI: %lld, 消息长度: %d", tai_sec, msg.len);

    // 设置 TTL（关键！）
    ctx->send_ttl = BLE_MESH_TTL_DEFAULT;

    // 发送消息
    err = bt_mesh_model_send(model, ctx, &msg, NULL, NULL);
    if (err) {
        APP_DBG("time_status_send> 发送失败: %d", err);
    } else {
        APP_DBG("time_status_send> 发送成功!");
    }
}
```

## 关键改动点

1. **使用 `NET_BUF_SIMPLE_DEFINE(msg, 32)`**
   - 在栈上定义缓冲区，而不是使用 `model->pub->msg`
   - 这是 CH592 Mesh 库的标准做法

2. **传递缓冲区地址 `&msg`**
   - `bt_mesh_model_msg_init(&msg, ...)`
   - `bt_mesh_model_send(model, ctx, &msg, ...)`

3. **设置 TTL**
   - `ctx->send_ttl = BLE_MESH_TTL_DEFAULT;`
   - 这是发送成功的关键

4. **简化代码**
   - 删除冗余的 NULL 检查和调试日志
   - 保持代码简洁

## 参考实现

成功的 Model 实现（Generic Level Model）：
```c
static void gen_level_status(struct bt_mesh_model *model, struct bt_mesh_msg_ctx *ctx)
{
    NET_BUF_SIMPLE_DEFINE(msg, 32);
    int err;

    bt_mesh_model_msg_init(&msg, BLE_MESH_MODEL_OP_GEN_LEVEL_STATUS);
    net_buf_simple_add_le16(&msg, mesh_level);

    ctx->send_ttl = BLE_MESH_TTL_DEFAULT;

    err = bt_mesh_model_send(model, ctx, &msg, NULL, NULL);
}
```

## 验证

编译固件后测试：
- ✅ 编译通过，无链接错误
- ✅ `bt_mesh_model_send()` 返回 0（成功）
- ✅ Android 应用能收到 Time Status 消息

## 注意事项

- **不需要清除 DataFlash**：这是代码逻辑修改，不影响存储的 Mesh 配置
- **CH592 特性**：必须使用栈上缓冲区，`model->pub->msg` 方式不可靠
- **TTL 设置**：所有 Model 发送消息前都需要设置 `ctx->send_ttl`
