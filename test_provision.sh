#!/bin/bash

echo "=== 配网测试脚本 ==="
echo ""

# 清空日志
adb -s 4e456a52 logcat -c

# 重启应用
echo "1. 重启应用..."
adb -s 4e456a52 shell am force-stop com.example.ble_device_mesh
sleep 1
adb -s 4e456a52 shell am start -n com.example.ble_device_mesh/.MainActivity
sleep 3

echo "2. 应用已启动"
echo ""
echo "请按以下步骤操作："
echo "  1. 在手机上点击「配网」按钮"
echo "  2. 等待扫描到未配网设备"
echo "  3. 点击未配网设备"
echo "  4. 观察配网进度"
echo ""
echo "等待 60 秒后查看日志..."
echo ""

# 实时监控日志
adb -s 4e456a52 logcat -s MeshApp:D BleConnection:D MeshManagerApi:V | while read line; do
    echo "$line"

    # 检测配网完成
    if echo "$line" | grep -q "配网完成"; then
        echo ""
        echo "=== ✅ 配网成功！ ==="
        break
    fi

    # 检测配网失败
    if echo "$line" | grep -q "配网失败"; then
        echo ""
        echo "=== ❌ 配网失败 ==="
        break
    fi

    # 检测断开连接
    if echo "$line" | grep -q "未配网设备已断开"; then
        echo ""
        echo "=== ⚠️ 设备断开连接 ==="
    fi
done
