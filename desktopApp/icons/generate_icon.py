#!/usr/bin/env python3
"""Generate the BiliMusicPlayer app icon.

Rounded-square gradient (#4FC3F7 -> #0277BD, top-left to bottom-right)
with a white beamed-eighth-note mark and a soft drop shadow.
Drawn at 4x supersampling, downscaled for antialiasing.

Outputs:
  desktopApp/src/main/resources/icon.png  512px runtime window/taskbar icon
  desktopApp/icons/icon.ico               16-256px for jpackage --icon
  desktopApp/icons/icon.icns              best effort (skipped on failure)
"""

import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

N = 1024          # design size
SS = 4            # supersampling factor
W = N * SS

TOP_LEFT = (0x4F, 0xC3, 0xF7)     # #4FC3F7
BOTTOM_RIGHT = (0x02, 0x77, 0xBD) # #0277BD
CORNER_RADIUS = 0.22 * W          # ~22% of side

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def make_gradient():
    """Diagonal gradient: value increases along (x + y)."""
    yy, xx = np.mgrid[0:W, 0:W].astype(np.float32)
    t = (xx + yy) / (2.0 * (W - 1))
    arr = np.zeros((W, W, 3), dtype=np.uint8)
    for c in range(3):
        arr[..., c] = (TOP_LEFT[c] + (BOTTOM_RIGHT[c] - TOP_LEFT[c]) * t).astype(np.uint8)
    return Image.fromarray(arr, "RGB")


def rounded_mask():
    mask = Image.new("L", (W, W), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, W - 1, W - 1], radius=int(CORNER_RADIUS), fill=255)
    return mask


def draw_note_layer(fill):
    """Draw the beamed-eighth-note mark at scale W into an RGBA layer."""
    layer = Image.new("RGBA", (W, W), (0, 0, 0, 0))

    # heads: (cx, cy, rx, ry); stems rise from each head's right edge
    heads = [
        (0.380 * W, 0.700 * W, 0.115 * W, 0.085 * W, 18),
        (0.640 * W, 0.640 * W, 0.115 * W, 0.085 * W, 18),
    ]
    sw = 0.048 * W  # stem width
    top = 0.245 * W

    def stem_and_beam(d):
        # each stem sits on its head's right edge (right edges aligned),
        # extending down past the head's vertical center so they connect
        s1x = heads[0][0] + heads[0][2] - sw
        s2x = heads[1][0] + heads[1][2] - sw
        d.rectangle([s1x, top, s1x + sw, heads[0][1] - 0.010 * W], fill=fill)
        d.rectangle([s2x, top + 0.020 * W, s2x + sw, heads[1][1] - 0.010 * W], fill=fill)
        # beam connecting stem tops, slanting slightly down to the right
        d.polygon(
            [
                (s1x, top),
                (s2x + sw, top + 0.020 * W),
                (s2x + sw, top + 0.118 * W),
                (s1x, top + 0.098 * W),
            ],
            fill=fill,
        )

    d = ImageDraw.Draw(layer)
    stem_and_beam(d)

    # Note heads: tilted ellipses drawn on separate layers then rotated.
    def head(cx, cy, rw, rh, angle):
        tile = Image.new("RGBA", (int(rw * 2 * 1.4), int(rh * 2 * 1.4)), (0, 0, 0, 0))
        td = ImageDraw.Draw(tile)
        margin = int(max(rw, rh) * 0.4)
        td.ellipse(
            [margin, margin, margin + int(rw * 2), margin + int(rh * 2)],
            fill=fill,
        )
        tile = tile.rotate(-angle, resample=Image.BICUBIC, expand=True)
        layer.paste(tile, (int(cx - tile.width / 2), int(cy - tile.height / 2)), tile)

    for cx, cy, rx, ry, angle in heads:
        head(cx, cy, rx, ry, angle)
    return layer


def build():
    # Background
    img = make_gradient().convert("RGBA")
    mask = rounded_mask()
    img.putalpha(mask)

    # Note (white), shifted slightly down-left for optical centering
    note = draw_note_layer((255, 255, 255, 255))
    dx, dy = int(-0.012 * W), int(0.012 * W)

    # Soft shadow: note silhouette in 20% black, offset down ~2% side, blurred
    shadow_src = note.split()[3]
    shadow = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    black = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    bd = ImageDraw.Draw(black)
    bd.bitmap((0, 0), shadow_src, fill=(0, 0, 0, 51))
    shadow = black.filter(ImageFilter.GaussianBlur(int(0.010 * W)))
    img.alpha_composite(shadow, (dx, int(dy + 0.020 * W)))
    img.alpha_composite(note, (dx, dy))

    # Outside the rounded square must stay transparent after composites
    img.putalpha(Image.composite(img.split()[3], Image.new("L", (W, W), 0), mask))

    base = img.resize((N, N), Image.LANCZOS)

    out_png = os.path.join(ROOT, "desktopApp/src/main/resources/icon.png")
    os.makedirs(os.path.dirname(out_png), exist_ok=True)
    base.resize((512, 512), Image.LANCZOS).save(out_png)
    print("wrote", out_png)

    out_ico = os.path.join(HERE, "icon.ico")
    base.save(out_ico, sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])
    print("wrote", out_ico)

    try:
        out_icns = os.path.join(HERE, "icon.icns")
        base.save(out_icns, sizes=[(s, s) for s in (16, 32, 64, 128, 256, 512)])
        print("wrote", out_icns)
    except Exception as e:  # ICNS writing is platform/format dependent
        print("icns skipped:", e)

    # Preview: 512 + the small sizes side by side
    sizes = (512, 128, 48, 32, 16)
    gap, margin = 24, 24
    pw = margin * 2 + sizes[0] + sum(sizes[1:]) + gap * len(sizes)
    ph = margin * 2 + sizes[0]
    prev = Image.new("RGB", (pw, ph), (240, 240, 240))
    x = margin
    for s in sizes:
        thumb = base.resize((s, s), Image.LANCZOS)
        prev.paste(thumb, (x, margin), thumb)
        x += s + gap
    prev_path = "/tmp/bmp-icon-preview.png"
    prev.save(prev_path)
    print("wrote", prev_path)


if __name__ == "__main__":
    build()
