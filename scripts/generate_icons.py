"""根据 images/app图标.png 生成所有 Android 图标资源"""
from PIL import Image
import os

SRC = "images/app图标.png"
DENSITIES = {
    "mdpi":   48,
    "hdpi":   72,
    "xhdpi":  96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

MIPMAP_BASE = "app/src/main/res"

def main():
    img = Image.open(SRC).convert("RGBA")
    print(f"源图: {img.size}, Mode={img.mode}")

    # 1. 生成各密度 mipmap PNG（覆盖原来的 webp）
    for density, size in DENSITIES.items():
        mipmap_dir = os.path.join(MIPMAP_BASE, f"mipmap-{density}")
        os.makedirs(mipmap_dir, exist_ok=True)

        resized = img.resize((size, size), Image.LANCZOS)
        # launcher icon
        path = os.path.join(mipmap_dir, "ic_launcher.png")
        resized.save(path, "PNG")
        print(f"  OK {path} ({size}x{size})")
        # round icon
        path_r = os.path.join(mipmap_dir, "ic_launcher_round.png")
        resized.save(path_r, "PNG")
        print(f"  OK {path_r} ({size}x{size})")

    # 2. 为自适应图标生成前景图 (放在 drawable 目录)
    # 自适应图标 viewport = 108dp, 取 2x 分辨率 216x216
    drawable_dir = os.path.join(MIPMAP_BASE, "../res/drawable")
    drawable_dir = os.path.normpath(drawable_dir)
    os.makedirs(drawable_dir, exist_ok=True)

    fg = img.resize((216, 216), Image.LANCZOS)
    fg_path = os.path.join(drawable_dir, "ic_app_foreground.png")
    fg.save(fg_path, "PNG")
    print(f"  OK {fg_path} (216x216)")

    # 3. 删除旧的 webp 文件 (避免编译冲突)
    for density in DENSITIES:
        mipmap_dir = os.path.join(MIPMAP_BASE, f"mipmap-{density}")
        for fname in ["ic_launcher.webp", "ic_launcher_round.webp"]:
            fpath = os.path.join(mipmap_dir, fname)
            if os.path.exists(fpath):
                os.remove(fpath)
                print(f"  DEL {fpath}")

    print("\n完成！所有图标已生成。")

if __name__ == "__main__":
    main()
