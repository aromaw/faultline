#!/usr/bin/env python3
"""Renders the app icon from the SVG sources (needs cairosvg and Pillow; neither is a build dependency).

  python3 assets/icon/render.py

writes assets/icon/png/faultline-<size>.png (16 ... 1024), faultline.icns (macOS), faultline.ico (Windows) and the
PNGs the GUI loads (src/main/resources/com/branchdiff/gui/icons/). 16 px uses the simplified faultline-16.svg.
"""
import io
import os
import struct

import cairosvg
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
RES = os.path.join(ROOT, "src/main/resources/com/branchdiff/gui/icons")
SIZES = [16, 32, 48, 64, 128, 256, 512, 1024]


def png(size):
    src = "faultline-16.svg" if size <= 16 else "faultline.svg"
    return cairosvg.svg2png(url=os.path.join(HERE, src), output_width=size, output_height=size)


def icns(images):
    # PNG-payload icon types understood by macOS 10.7+
    types = [(b"icp4", 16), (b"icp5", 32), (b"icp6", 48), (b"ic07", 128), (b"ic08", 256), (b"ic09", 512),
             (b"ic10", 1024), (b"ic11", 32), (b"ic12", 64), (b"ic13", 256), (b"ic14", 512)]
    body = b"".join(t + struct.pack(">I", 8 + len(images[s])) + images[s] for t, s in types)
    return b"icns" + struct.pack(">I", 8 + len(body)) + body


def main():
    os.makedirs(os.path.join(HERE, "png"), exist_ok=True)
    os.makedirs(RES, exist_ok=True)
    images = {s: png(s) for s in SIZES}
    for s, data in images.items():
        with open(os.path.join(HERE, "png", f"faultline-{s}.png"), "wb") as f:
            f.write(data)
        if s <= 512:
            with open(os.path.join(RES, f"faultline-{s}.png"), "wb") as f:
                f.write(data)
    with open(os.path.join(HERE, "faultline.icns"), "wb") as f:
        f.write(icns(images))
    big = Image.open(io.BytesIO(images[256]))
    big.save(os.path.join(HERE, "faultline.ico"), sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
             append_images=[Image.open(io.BytesIO(images[s])) for s in (16, 32, 48, 64, 128)])


if __name__ == "__main__":
    main()
