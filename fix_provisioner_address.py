#!/usr/bin/env python3
"""
修复 Mesh 配置文件中的 Provisioner 地址

用法：
    python fix_provisioner_address.py <json_file> <new_address>

示例：
    python fix_provisioner_address.py "小米9 .json" 0x0001
"""

import json
import sys
import shutil
from datetime import datetime

def fix_provisioner_address(json_file, new_address):
    """修改 JSON 配置文件中的 Provisioner 地址"""

    # 备份原文件
    backup_file = f"{json_file}.backup_{datetime.now().strftime('%Y%m%d_%H%M%S')}"
    shutil.copy2(json_file, backup_file)
    print(f"✓ 已备份原文件到: {backup_file}")

    # 读取 JSON
    with open(json_file, 'r', encoding='utf-8') as f:
        data = json.load(f)

    # 转换地址格式
    if isinstance(new_address, str):
        if new_address.startswith('0x'):
            new_address = new_address[2:]
        new_address = new_address.upper().zfill(4)
    else:
        new_address = f"{new_address:04X}"

    print(f"\n目标 Provisioner 地址: 0x{new_address}")

    # 查找并修改 Provisioner 地址
    modified = False
    if 'provisioners' in data:
        for provisioner in data['provisioners']:
            old_address = provisioner.get('unicastAddress', 'N/A')
            provisioner['unicastAddress'] = new_address
            print(f"✓ 修改 Provisioner '{provisioner.get('provisionerName', 'Unknown')}': {old_address} → {new_address}")
            modified = True

    if not modified:
        print("✗ 未找到 provisioners 配置")
        return False

    # 保存修改后的文件
    with open(json_file, 'w', encoding='utf-8') as f:
        json.dump(data, f, indent=2, ensure_ascii=False)

    print(f"\n✓ 配置文件已更新: {json_file}")
    print("\n下一步操作：")
    print("1. 在你的 App 中重新导入此 JSON 文件")
    print("2. 确认 App 主界面显示的 Provisioner 地址为 0x" + new_address)
    print("3. 尝试控制设备")

    return True

def main():
    if len(sys.argv) < 3:
        print("用法: python fix_provisioner_address.py <json_file> <new_address>")
        print("示例: python fix_provisioner_address.py '小米9 .json' 0x0001")
        sys.exit(1)

    json_file = sys.argv[1]
    new_address = sys.argv[2]

    try:
        fix_provisioner_address(json_file, new_address)
    except Exception as e:
        print(f"✗ 错误: {e}")
        sys.exit(1)

if __name__ == '__main__':
    main()
