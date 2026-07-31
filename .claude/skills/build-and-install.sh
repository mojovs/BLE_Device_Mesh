#!/bin/bash
# 编译并安装 Android App

set -e

echo "=== 开始编译 Android App ==="
./gradlew assembleDebug

echo "=== 安装到测试设备 ==="
adb -s 4e456a52 install -r app/build/outputs/apk/debug/app-debug.apk

echo "=== 启动应用 ==="
adb -s 4e456a52 shell am start -n com.example.ble_device_mesh/.MainActivity

echo "=== 完成 ==="
