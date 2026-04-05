#!/bin/bash

# 温度获取问题诊断脚本
# 使用方法: ./diagnose_temperature.sh

echo "==================================="
echo "温度获取问题诊断"
echo "==================================="
echo ""

# 1. 检查 Android 日志
echo "1. 检查 Android 日志 (最近 100 行)"
echo "-----------------------------------"
adb logcat -d | grep -E "MeshApp|Sensor|Temperature|温度" | tail -100

echo ""
echo "2. 清除日志缓存"
echo "-----------------------------------"
adb logcat -c
echo "日志已清除"

echo ""
echo "3. 等待新的日志..."
echo "-----------------------------------"
echo "请在 App 中点击【读取温度】按钮"
echo "按 Ctrl+C 停止监控"
echo ""

# 实时监控日志
adb logcat | grep --line-buffered -E "MeshApp|Sensor|Temperature|温度" | while read line; do
    echo "$line"
    
    # 检查关键信息
    if echo "$line" | grep -q "Sensor Get 已发送"; then
        echo "✓ App 已发送 Sensor Get 消息"
    fi
    
    if echo "$line" | grep -q "收到 SensorStatus"; then
        echo "✓ App 收到 SensorStatus 响应"
    fi
    
    if echo "$line" | grep -q "解析到温度"; then
        echo "✓ 温度解析成功"
    fi
    
    if echo "$line" | grep -q "未找到 App Key"; then
        echo "✗ 错误: 未找到 App Key"
    fi
    
    if echo "$line" | grep -q "BLE 未连接"; then
        echo "✗ 错误: BLE 未连接"
    fi
    
    if echo "$line" | grep -q "未找到设备"; then
        echo "✗ 错误: 未找到设备"
    fi
done
