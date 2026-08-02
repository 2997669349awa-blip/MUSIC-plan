#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把生成的图标 jpg 转为 png 并缩放到各 mipmap 密度，同时生成 round 版本"""
from PIL import Image
import os

SRC = "/workspace/FileManager/musicplugin/src/main/res/mipmap-xxxhdpi/ic_launcher.jpg"
RES = "/workspace/FileManager/musicplugin/src/main/res"

# Android launcher icon 密度尺寸
DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

img = Image.open(SRC).convert("RGBA")
print("source size:", img.size)

for folder, size in DENSITIES.items():
    out_dir = os.path.join(RES, folder)
    os.makedirs(out_dir, exist_ok=True)
    resized = img.resize((size, size), Image.LANCZOS)
    # ic_launcher.png
    resized.save(os.path.join(out_dir, "ic_launcher.png"), "PNG")
    # ic_launcher_round.png（同一张图，系统会套圆形蒙版）
    resized.save(os.path.join(out_dir, "ic_launcher_round.png"), "PNG")
    print(f"  {folder}: {size}x{size} OK")

# 删除原始 jpg
os.remove(SRC)
print("done; removed source jpg")
