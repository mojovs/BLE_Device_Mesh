# Sensor Model 数据格式分析

![Sensor Model 位域图](images/sensor_model_bitfield.drawio.png)

---

## 1. 消息交互流程

手机（nRF Mesh App）主动发起查询，设备响应，不使用 Publish 主动推送：

```
手机 (nRF Mesh App)                    CH592 (Sensor Server)
        |                                       |
        |--- Sensor Get  (opcode: 0x8231) ----->|
        |                                       |
        |<-- Sensor Status (opcode: 0x0052) ----|
```

- `sensor_pub.update = NULL`，不主动推送
- 手机在 nRF Mesh App 点 "Read" → 触发 Sensor Get → 设备回 Sensor Status

---

## 2. Sensor Status 报文格式（4字节 payload）

| 字节 | 字段 | 值 | 说明 |
|------|------|----|------|
| byte[0] | MPID [7:0]  | 0xE3 | MPID 低字节 |
| byte[1] | MPID [15:8] | 0x0E | MPID 高字节 |
| byte[2] | Value [7:0] | 低字节 | Temperature 低字节 |
| byte[3] | Value [15:8]| 高字节 | Temperature 高字节 |

---

## 3. MPID 位域详解（Format B，2字节 Little-Endian）

```
bit:  15  14  13  12  11  10   9   8   7   6   5 | 4   3   2 | 1 | 0
      |<------- Property ID [10:0] = 0x0071 ------>|<Len-1=1 >|Fmt|Fmt|
                                                               [1] [0]
                                                                0    1  <- 关键
```

| 字段 | 位 | 值 | 说明 |
|------|----|----|------|
| Property ID | bits[15:5] | 0x0071 | Present Indoor Ambient Temperature |
| Length - 1  | bits[4:2]  | 0b001  | 数据长度 2字节（Length-1=1） |
| Format      | bits[1:0]  | 0b01   | bit[0]=1 表示 Format B（2字节MPID） |

**计算过程：**

```c
uint16_t mpid = (uint16_t)((0x0071 << 5) | (1 << 1) | 1);
// = 0x0E20 | 0x02 | 0x01
// = 0x0EE3
// 小端存储 -> byte[0]=0xE3, byte[1]=0x0E
```

---

## 4. Temperature 编码（2字节，单位 0.01°C）

**编码公式：**

```c
encoded = raw_data * 625 / 100
```

**原理：**
- DS18B20 12-bit 精度：1 LSB = 0.0625°C
- Temperature 精度：1 LSB = 0.01°C
- 换算系数：0.0625 / 0.01 = 6.25 = 625/100

**代码实现：**

```c
void sensor_update_temperature(int16_t raw_data)
{
    // DS18B20: 1 LSB = 0.0625°C (12-bit)
    // Temperature: 1 LSB = 0.01°C
    // encoded = raw * 0.0625 / 0.01 = raw * 625 / 100
    current_temp_encoded = (int16_t)(raw_data * 625 / 100);
}
```

**示例：**

| 实际温度 | raw_data | encoded (dec) | 小端字节 |
|---------|---------|--------------|---------|
| 20.3125 °C | 325  | 2031  | 0xEF 0x07 |
| 25.0 °C    | 400  | 2500  | 0xC4 0x09 |
| 36.5 °C    | 584  | 3650  | 0x42 0x0E |
| -10.0 °C   | -160 | -1000 | 0x18 0xFC |

- 范围：-273.15°C ~ 327.67°C（int16_t: -32768 ~ 32767）
- Property ID：0x0071（Present Indoor Ambient Temperature）
- 精度：0.01°C（比 Temperature 8 的 0.5°C 精度高 50 倍）

---

## 5. Sensor Descriptor Status 格式（7字节）

| 字节 | 字段 | 值 | 说明 |
|------|------|----|------|
| byte[0..1] | Property ID       | 0x0071 (LE) | 温度属性 ID |
| byte[2..4] | Tolerance         | 0x000000    | Positive + Negative Tolerance（各12bit） |
| byte[5]    | Sampling Function | 0x00        | Unspecified |
| byte[6]    | Measurement Period | 0x00       | Not applicable |
| byte[7]    | Update Interval   | 0x00        | Not applicable |

---

## 6. 关键代码

```c
#define SENSOR_PROP_ID_TEMPERATURE  0x0071

static void sensor_get(struct bt_mesh_model *model,
                       struct bt_mesh_msg_ctx *ctx,
                       struct net_buf_simple *buf)
{
    NET_BUF_SIMPLE_DEFINE(msg, 8);
    bt_mesh_model_msg_init(&msg, BLE_MESH_MODEL_OP_SENSOR_STATUS);

    // Format B MPID: bit[0]=1, bits[3:1]=1(len-1, 2bytes), bits[15:5]=Property ID
    uint16_t mpid = (uint16_t)((SENSOR_PROP_ID_TEMPERATURE << 5) | (1 << 1) | 1);
    net_buf_simple_add_le16(&msg, mpid);
    net_buf_simple_add_le16(&msg, (uint16_t)current_temp_encoded);  // 2字节 LE

    bt_mesh_model_send(model, ctx, &msg, NULL, NULL);
}
```
