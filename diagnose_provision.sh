#!/bin/bash

echo "=== BLE Mesh 配网问题诊断脚本 ==="
echo ""

# 检查设备连接
echo "1. 检查 Android 设备连接..."
adb devices | grep -q "4e456a52"
if [ $? -ne 0 ]; then
    echo "   ❌ 设备未连接"
    exit 1
fi
echo "   ✅ 设备已连接"
echo ""

# 检查应用是否安装
echo "2. 检查应用是否安装..."
adb -s 4e456a52 shell pm list packages | grep -q "com.example.ble_device_mesh"
if [ $? -ne 0 ]; then
    echo "   ❌ 应用未安装"
    exit 1
fi
echo "   ✅ 应用已安装"
echo ""

# 清空日志
echo "3. 清空日志..."
adb -s 4e456a52 logcat -c
echo "   ✅ 日志已清空"
echo ""

# 启动应用
echo "4. 启动应用..."
adb -s 4e456a52 shell am force-stop com.example.ble_device_mesh
sleep 1
adb -s 4e456a52 shell am start -n com.example.ble_device_mesh/.MainActivity
sleep 3
echo "   ✅ 应用已启动"
echo ""

# 检查网络加载
echo "5. 检查 Mesh 网络加载..."
adb -s 4e456a52 logcat -d | grep -q "网络加载成功"
if [ $? -ne 0 ]; then
    echo "   ❌ 网络加载失败"
    adb -s 4e456a52 logcat -d | grep "MeshApp" | tail -20
    exit 1
fi
echo "   ✅ 网络加载成功"
echo ""

# 检查 Key 信息
echo "6. 检查 NetKey 和 AppKey..."
adb -s 4e456a52 logcat -d | grep -A 5 "网络 Key 信息"
echo ""

echo "=== 准备测试配网流程 ==="
echo ""
echo "请按以下步骤操作："
echo "  1. 在手机上点击「配网」按钮"
echo "  2. 等待扫描到未配网设备"
echo "  3. 点击未配网设备"
echo "  4. 观察配网进度"
echo ""
echo "等待 40 秒后查看日志..."
echo ""

# 等待用户操作
sleep 40

echo "=== 配网日志分析 ==="
echo ""

# 检查是否进入配网界面
echo "1. 检查是否进入配网界面..."
adb -s 4e456a52 logcat -d | grep -q "开始扫描未配网设备"
if [ $? -ne 0 ]; then
    echo "   ❌ 未进入配网界面"
    exit 1
fi
echo "   ✅ 已进入配网界面"
echo ""

# 检查是否发现未配网设备
echo "2. 检查是否发现未配网设备..."
adb -s 4e456a52 logcat -d | grep -q "开始配网设备"
if [ $? -ne 0 ]; then
    echo "   ❌ 未发现或未点击未配网设备"
    exit 1
fi
echo "   ✅ 已点击未配网设备"
echo ""

# 检查 BLE 连接
echo "3. 检查 BLE 连接..."
adb -s 4e456a52 logcat -d | grep -q "已连接到未配网设备"
if [ $? -ne 0 ]; then
    echo "   ❌ BLE 连接失败"
    exit 1
fi
echo "   ✅ BLE 已连接"
echo ""

# 检查服务发现
echo "4. 检查服务发现..."
adb -s 4e456a52 logcat -d | grep -q "找到 Mesh Provisioning Service"
if [ $? -ne 0 ]; then
    echo "   ❌ 未找到 Provisioning Service"
    exit 1
fi
echo "   ✅ 找到 Provisioning Service"
echo ""

# 检查通知启用
echo "5. 检查通知启用..."
adb -s 4e456a52 logcat -d | grep -q "Descriptor 写入成功，通知已启用"
if [ $? -ne 0 ]; then
    echo "   ❌ 通知启用失败"
    exit 1
fi
echo "   ✅ 通知已启用"
echo ""

# 检查 onServicesDiscovered 回调
echo "6. 检查 onServicesDiscovered 回调..."
adb -s 4e456a52 logcat -d | grep -q "服务发现完成，开始配网流程"
if [ $? -ne 0 ]; then
    echo "   ❌ onServicesDiscovered 回调未触发"
    echo "   这是之前修复的问题，请确认已安装最新版本"
    exit 1
fi
echo "   ✅ onServicesDiscovered 回调已触发"
echo ""

# 检查配网前检查
echo "7. 检查配网前检查..."
adb -s 4e456a52 logcat -d | grep -A 5 "配网前检查"
echo ""

# 检查 identifyNode 调用
echo "8. 检查 identifyNode 调用..."
adb -s 4e456a52 logcat -d | grep -q "调用 identifyNode"
if [ $? -ne 0 ]; then
    echo "   ❌ identifyNode 未调用"
    exit 1
fi
echo "   ✅ identifyNode 已调用"
echo ""

# 检查配网 PDU 发送
echo "9. 检查配网 PDU 发送..."
adb -s 4e456a52 logcat -d | grep "发送配网 PDU" | head -5
echo ""

# 检查配网状态变化
echo "10. 检查配网状态变化..."
adb -s 4e456a52 logcat -d | grep "配网状态变化"
echo ""

# 检查是否收到设备响应
echo "11. 检查是否收到设备响应..."
adb -s 4e456a52 logcat -d | grep "收到 BLE 数据" | wc -l
response_count=$(adb -s 4e456a52 logcat -d | grep "收到 BLE 数据" | wc -l)
if [ "$response_count" -eq "0" ]; then
    echo "   ❌ 未收到设备响应"
    echo "   这可能是固件端问题："
    echo "   - 固件未正确处理配网邀请"
    echo "   - 固件未启用 PB-GATT 配网"
    echo "   - 固件端 BLE 连接有问题"
else
    echo "   ✅ 收到 $response_count 个 BLE 数据包"
fi
echo ""

echo "=== 完整配网日志 ==="
adb -s 4e456a52 logcat -d | grep -E "(配网|Provision|BleConnection)" | tail -50
