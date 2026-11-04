#!/usr/bin/env python3
"""Renders the app icon from the SVG sources (needs cairosvg and Pillow; neither is a build dependency).

  python3 assets/icon/render.py

writes assets/icon/png/faultline-<size>.png (16 ... 1024), faultline.ico (Windows) and the PNGs the GUI loads
(src/main/resources/com/branchdiff/gui/icons/). 16 px uses the simplified faultline-16.svg.

macOS draws every app icon on Apple's grid: an 824 x 824 rounded square centred on a transparent 1024 x 1024 canvas,
with a soft shadow below it. A full-bleed icon looks a size larger than every other icon in the Dock, so
faultline.icns and the Dock image (icons/faultline-mac-512.png) use that grid; the other PNGs stay full-bleed, as
Windows and Linux icons are.
"""
import io
import os
import struct

import cairosvg
from PIL import Image, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
RES = os.path.join(ROOT, "src/main/resources/com/branchdiff/gui/icons")
SIZES = [16, 32, 48, 64, 128, 256, 512, 1024]


def png(size):
    src = "faultline-16.svg" if size <= 16 else "faultline.svg"
    return cairosvg.svg2png(url=os.path.join(HERE, src), output_width=size, output_height=size)


# the icon's rounded square spans 60 of the SVG's 64 units; on Apple's grid it spans 824 of 1024
MAC_BODY = 824 / 1024
SVG_BODY = 60 / 64


def mac_png(size):
    """the icon on Apple's grid: scaled into the 824/1024 square, centred, with a soft drop shadow"""
    big = 1024
    inner = round(big * MAC_BODY / SVG_BODY)
    art = Image.open(io.BytesIO(cairosvg.svg2png(url=os.path.join(HERE, "faultline.svg"),
                                                 output_width=inner, output_height=inner))).convert("RGBA")
    canvas = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    off = (big - inner) // 2
    # shadow: the icon's silhouette, 30 % black, 10 px lower, blurred (Apple's template: y 12 px, blur 28 px)
    alpha = art.getchannel("A").point(lambda a: a * 30 // 100)
    shadow = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    shadow.paste(Image.new("RGBA", art.size, (0, 0, 0, 255)), (off, off + 10), alpha)
    canvas = Image.alpha_composite(canvas, shadow.filter(ImageFilter.GaussianBlur(14)))
    canvas.alpha_composite(art, (off, off))
    if size != big:
        canvas = canvas.resize((size, size), Image.LANCZOS)
    out = io.BytesIO()
    canvas.save(out, "PNG", optimize=True)
    return out.getvalue()


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
    mac = {s: mac_png(s) for s in SIZES}
    with open(os.path.join(HERE, "faultline.icns"), "wb") as f:
        f.write(icns(mac))
    with open(os.path.join(RES, "faultline-mac-512.png"), "wb") as f:
        f.write(mac[512])
    big = Image.open(io.BytesIO(images[256]))
    big.save(os.path.join(HERE, "faultline.ico"), sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
             append_images=[Image.open(io.BytesIO(images[s])) for s in (16, 32, 48, 64, 128)])


if __name__ == "__main__":
    main()
