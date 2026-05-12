#!/usr/bin/env python3
"""Android 应用图标生成工具

用法:
    python scripts/generate_icons.py <图片路径>

示例:
    python scripts/generate_icons.py images/app图标.png

说明:
    将一张方形原图缩放生成所有 mipmap 密度目录下的 ic_launcher.png / ic_launcher_round.png，
    以及 drawable 目录下的 ic_app_foreground.png（自适应图标前景），
    并清理旧的 .webp 图标文件。
"""
import sys
import os
from PIL import Image

# 各 mipmap 密度对应的像素尺寸
DENSITIES = {
    "mdpi":    48,
    "hdpi":    72,
    "xhdpi":   96,
    "xxhdpi":  144,
    "xxxhdpi": 192,
}

# 自适应图标前景图尺寸（viewport 108dp × 2）
FOREGROUND_SIZE = 216

MIPMAP_BASE = "app/src/main/res"
DRAWABLE_DIR = "app/src/main/res/drawable"


def make_icons(src_path: str) -> None:
    img = Image.open(src_path).convert("RGBA")
    print(f"源图: {img.size}, Mode={img.mode}")

    # 1. 生成各密度的 mipmap PNG
    for density, size in DENSITIES.items():
        mipmap_dir = os.path.join(MIPMAP_BASE, f"mipmap-{density}")
        os.makedirs(mipmap_dir, exist_ok=True)

        resized = img.resize((size, size), Image.LANCZOS)

        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            path = os.path.join(mipmap_dir, name)
            resized.save(path, "PNG")
            print(f"  -> {path} ({size}x{size})")

        # 删除旧的 webp 文件
        for old in ("ic_launcher.webp", "ic_launcher_round.webp"):
            old_path = os.path.join(mipmap_dir, old)
            if os.path.exists(old_path):
                os.remove(old_path)
                print(f"  DEL {old_path}")

    # 2. 生成自适应图标前景图
    os.makedirs(DRAWABLE_DIR, exist_ok=True)
    fg = img.resize((FOREGROUND_SIZE, FOREGROUND_SIZE), Image.LANCZOS)
    fg_path = os.path.join(DRAWABLE_DIR, "ic_app_foreground.png")
    fg.save(fg_path, "PNG")
    print(f"  -> {fg_path} ({FOREGROUND_SIZE}x{FOREGROUND_SIZE})")

    print("\n完成！所有图标已生成。")


def main():
    if len(sys.argv) < 2:
        print(f"用法: python {sys.argv[0]} <图片路径>")
        print(f"示例: python {sys.argv[0]} images/app图标.png")
        sys.exit(1)

    src = sys.argv[1]
    if not os.path.isfile(src):
        print(f"错误: 文件不存在 - {src}")
        sys.exit(1)

    make_icons(src)


if __name__ == "__main__":
    main()
