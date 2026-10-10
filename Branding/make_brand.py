#!/usr/bin/env python3
"""WiFiBar brand kit generator. Every graphic is plotted from math; nothing is hand-drawn.

The math idea ("Sweep")
-----------------------
WiFiBar's Locate feature has you turn in a circle. It smooths the signal around you with a
circular Gaussian of width sigma = 25 degrees (DirectionSweep in the app). One strong direction,
plotted in polar form, is the lobe

        r(phi) = exp(-phi^2 / (2 sigma^2)),   phi = theta - theta0,   sigma = 25 deg,

a beam from you toward the strongest direction (theta0 = 45 deg). The router is the dot on
the sweep circle at the end of the beam, and a faint arc of that circle shows the turn.
The icon body is the superellipse |x|^5 + |y|^5 = 1 (Apple's rounded square), 824 px on a
1024 px canvas, filled with a vertical gradient.

How to run
----------
    python3 "Branding/make_brand.py"        (any working directory)

Needs Python 3 with matplotlib, numpy and Pillow, plus /usr/bin/iconutil for AppIcon.icns.
Everything is written to Branding/out/. Nothing is random, so re-running gives
byte-identical files.
"""

import io
import shutil
import subprocess
import sys
from pathlib import Path

import numpy as np
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.collections import LineCollection  # noqa: E402
from matplotlib.colors import to_rgb  # noqa: E402
from matplotlib.patches import Circle, Polygon  # noqa: E402
from PIL import Image, ImageDraw, ImageFilter, ImageFont  # noqa: E402

HERE = Path(__file__).resolve().parent
OUT = HERE / "out"

# ---------------------------------------------------------------- palette
INK = "#0A1A3A"       # icon background (bottom), mark on light, text
BLUE = "#1B4F9E"      # icon background (top)
CYAN = "#46C8FF"      # glow around the router dot, accents
PALE = "#A6DCFF"      # tail of the beam gradient
WHITE = "#FFFFFF"     # the mark on the icon and on dark
MIST = "#F5F5F7"      # light neutral background
GRAPHITE = "#1C1C1E"  # dark neutral background
PALETTE = [
    ("Ink Navy", INK, "Icon gradient (bottom), mark on light, text"),
    ("Signal Blue", BLUE, "Icon gradient (top)"),
    ("Beam Cyan", CYAN, "Glow at the router dot; sparing accents"),
    ("Beam Pale", PALE, "Tail of the beam gradient"),
    ("White", WHITE, "The mark on the icon and on dark"),
    ("Graphite", GRAPHITE, "Dark neutral background"),
]

# ---------------------------------------------------------------- geometry
SIGMA = np.deg2rad(25.0)   # the app's direction-sweep smoothing width
BEARING = np.deg2rad(45.0)
CANVAS = 1024
BODY = 824                 # Apple icon grid: 100 px margin on a 1024 canvas
SQUIRCLE_N = 5.0

# Mark proportions in pixels at 1024. "small" is the optical size used for 16 and 32 px:
# bigger dot and gap, no glow, no arc.
REGULAR = dict(L=436.0, gap=40.0, dot_r=46.0, glow=True, rim=True, grad=(PALE, WHITE))
SMALL = dict(L=420.0, gap=66.0, dot_r=66.0, glow=False, rim=False, grad=(WHITE, WHITE))


def px2pt(px):
    """Line widths: figures use dpi 100, so 1 pt = 100/72 px."""
    return px * 72.0 / 100.0


def superellipse(cx, cy, half, n=SQUIRCLE_N, count=2400):
    t = np.linspace(0, 2 * np.pi, count, endpoint=False)
    c, s = np.cos(t), np.sin(t)
    x = np.sign(c) * np.abs(c) ** (2.0 / n)
    y = np.sign(s) * np.abs(s) ** (2.0 / n)
    return np.c_[cx + half * x, cy + half * y]


def mark_geometry(scale=1.0, L=REGULAR["L"], gap=REGULAR["gap"], dot_r=REGULAR["dot_r"], center=(0.0, 0.0)):
    """Beam polygon, dot and sweep-circle centre in pixel coordinates (y up), centred on `center`."""
    L, gap, dot_r = L * scale, gap * scale, dot_r * scale
    u = np.array([np.cos(BEARING), np.sin(BEARING)])
    phi = np.linspace(-np.pi, np.pi, 4001)
    r = L * np.exp(-phi ** 2 / (2 * SIGMA ** 2))
    beam = np.c_[r * np.cos(phi + BEARING), r * np.sin(phi + BEARING)]  # origin at (0, 0)
    dot = u * (L + gap + dot_r)
    # Centre the bounding box of beam + dot on `center`.
    lo = np.minimum(beam.min(axis=0), dot - dot_r)
    hi = np.maximum(beam.max(axis=0), dot + dot_r)
    shift = np.asarray(center, float) - (lo + hi) / 2
    return dict(beam=beam + shift, dot=dot + shift, dot_r=dot_r, origin=shift, u=u,
                R=L + gap + dot_r, L=L, extent=(hi - lo))


# ---------------------------------------------------------------- matplotlib helpers
def canvas(px):
    fig = plt.figure(figsize=(px / 100, px / 100), dpi=100)
    ax = fig.add_axes([0, 0, 1, 1])
    ax.set_xlim(0, px)
    ax.set_ylim(0, px)
    ax.axis("off")
    fig.patch.set_alpha(0)
    ax.patch.set_alpha(0)
    return fig, ax


def to_image(fig):
    buf = io.BytesIO()
    fig.savefig(buf, format="png", dpi=100, transparent=True, metadata={"Software": None})
    plt.close(fig)
    buf.seek(0)
    im = Image.open(buf)
    im.load()
    return im.convert("RGBA")


def gradient_image(px, p0, direction, length, c0, c1):
    """Linear gradient sampled on a px x px grid (pixel centres, y up)."""
    xs = np.arange(px) + 0.5
    X, Y = np.meshgrid(xs, xs)
    t = np.clip(((X - p0[0]) * direction[0] + (Y - p0[1]) * direction[1]) / length, 0, 1)
    a, b = np.array(to_rgb(c0)), np.array(to_rgb(c1))
    rgb = a + (b - a) * t[..., None]
    return np.dstack([rgb, np.ones_like(t)])


def draw_mark(ax, px, geo, color=None, grad=None, glow=False, rim=False, clip=None, glow_sigma=165.0, glow_alpha=0.30):
    """Draw the Sweep mark. `color` = flat colour; otherwise `grad` = (tail, tip) colours."""
    if glow:
        xs = np.arange(px) + 0.5
        X, Y = np.meshgrid(xs, xs)
        g = np.exp(-((X - geo["dot"][0]) ** 2 + (Y - geo["dot"][1]) ** 2) / (2 * glow_sigma ** 2))
        rgba = np.zeros((px, px, 4))
        rgba[..., :3] = to_rgb(CYAN)
        rgba[..., 3] = glow_alpha * g
        im = ax.imshow(rgba, extent=(0, px, 0, px), origin="lower", interpolation="nearest")
        if clip is not None:
            im.set_clip_path(clip)
    if rim:
        # A short arc of the sweep circle through the dot, fading out at both ends.
        span = np.deg2rad(34.0)
        a = np.linspace(BEARING - span, BEARING + span, 400)
        pts = geo["origin"] + geo["R"] * np.c_[np.cos(a), np.sin(a)]
        segs = np.stack([pts[:-1], pts[1:]], axis=1)
        t = np.abs((a[:-1] + a[1:]) / 2 - BEARING) / span
        alpha = 0.30 * np.cos(np.pi * t / 2) ** 2
        cols = np.c_[np.ones((len(t), 3)), alpha]
        lc = LineCollection(segs, colors=cols, linewidths=px2pt(5.0 * px / CANVAS), capstyle="round")
        ax.add_collection(lc)
    poly = Polygon(geo["beam"], closed=True, fc=color if color else "none", ec="none")
    ax.add_patch(poly)
    if color is None:
        im = ax.imshow(gradient_image(px, geo["origin"], geo["u"], geo["L"], *grad),
                       extent=(0, px, 0, px), origin="lower", interpolation="nearest")
        im.set_clip_path(poly)
    ax.add_patch(Circle(tuple(geo["dot"]), geo["dot_r"], fc=color if color else WHITE, ec="none"))


# ---------------------------------------------------------------- app icon
def squircle_mask(px, half, cx=None, cy=None, n=SQUIRCLE_N, ss=4):
    """Anti-aliased superellipse coverage mask (L mode), supersampled ss x ss."""
    cx = px / 2 if cx is None else cx
    cy = px / 2 if cy is None else cy
    xs = (np.arange(px * ss) + 0.5) / ss
    X, Y = np.meshgrid(xs, xs)
    inside = (np.abs((X - cx) / half) ** n + np.abs((Y - cy) / half) ** n) <= 1.0
    cov = inside.reshape(px, ss, px, ss).mean(axis=(1, 3))
    return Image.fromarray(np.round(cov * 255).astype(np.uint8), "L")


def render_icon(style):
    px, c, half = CANVAS, CANVAS / 2, BODY / 2
    fig, ax = canvas(px)
    body = Polygon(superellipse(c, c, half), closed=True, fc="none", ec="none")
    ax.add_patch(body)
    # Background: vertical gradient, Signal Blue at the top to Ink Navy at the bottom.
    bg = gradient_image(px, (0, c + half), (0, -1), BODY, BLUE, INK)
    im = ax.imshow(bg, extent=(0, px, 0, px), origin="lower", interpolation="nearest")
    im.set_clip_path(body)
    geo = mark_geometry(L=style["L"], gap=style["gap"], dot_r=style["dot_r"], center=(c, c))
    draw_mark(ax, px, geo, grad=style["grad"], glow=style["glow"], rim=style["rim"], clip=body)
    # Faint inner edge so the body holds its shape on dark desktops.
    edge = Polygon(superellipse(c, c, half), closed=True, fill=False, ec=(1, 1, 1, 0.12), lw=px2pt(4))
    ax.add_patch(edge)
    edge.set_clip_path(body)
    art = to_image(fig)
    # Drop shadow inside the 100 px margin (Apple's icon template has one).
    mask = squircle_mask(px, half)
    shadow_alpha = mask.filter(ImageFilter.GaussianBlur(14)).point(lambda v: int(round(v * 0.30)))
    shadow = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    shadow.paste(Image.new("RGBA", (px, px), (0, 0, 0, 255)), (0, 10), shadow_alpha)
    out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    out.alpha_composite(shadow)
    out.alpha_composite(art)
    return out


def resize(img, size):
    return img if img.size[0] == size else img.resize((size, size), Image.LANCZOS)


ICONSET = [  # file name, pixel size, optical style
    ("icon_16x16.png", 16, "small"),
    ("icon_16x16@2x.png", 32, "small"),
    ("icon_32x32.png", 32, "small"),
    ("icon_32x32@2x.png", 64, "regular"),
    ("icon_128x128.png", 128, "regular"),
    ("icon_128x128@2x.png", 256, "regular"),
    ("icon_256x256.png", 256, "regular"),
    ("icon_256x256@2x.png", 512, "regular"),
    ("icon_512x512.png", 512, "regular"),
    ("icon_512x512@2x.png", 1024, "regular"),
]


def save(img, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, format="PNG")


# ---------------------------------------------------------------- mark images
def render_mark(px, mark_px, color=None, grad=None, glow=False, rim=False, glow_sigma=None, glow_alpha=0.30,
                style=REGULAR, center=None):
    """The mark alone on transparent. `mark_px` is the length of the bounding box's longer side."""
    base = mark_geometry(L=style["L"], gap=style["gap"], dot_r=style["dot_r"])
    scale = mark_px / max(base["extent"])
    center = (px / 2, px / 2) if center is None else center
    geo = mark_geometry(scale, L=style["L"], gap=style["gap"], dot_r=style["dot_r"], center=center)
    fig, ax = canvas(px)
    draw_mark(ax, px, geo, color=color, grad=grad, glow=glow, rim=rim,
              glow_sigma=glow_sigma or 165.0 * scale, glow_alpha=glow_alpha)
    return to_image(fig), geo


# ---------------------------------------------------------------- fonts
SF_PRO = "/System/Library/Fonts/SFNS.ttf"
HELVETICA = "/System/Library/Fonts/HelveticaNeue.ttc"
_FONT_NOTE = {}


def font(size, weight=400):
    """SF Pro (variable) at the given weight and a matching optical size; Helvetica Neue fallback."""
    try:
        f = ImageFont.truetype(SF_PRO, size)
        values = []
        for axis in f.get_variation_axes():
            name = axis["name"].decode() if isinstance(axis["name"], bytes) else axis["name"]
            if name == "Weight":
                values.append(weight)
            elif name == "Optical Size":
                values.append(min(max(size * 0.75, axis["minimum"]), axis["maximum"]))
            else:
                values.append(axis["default"])
        f.set_variation_by_axes(values)
        _FONT_NOTE["face"] = "SF Pro"
        return f
    except Exception:
        pass
    try:
        index = {400: 0, 500: 10, 600: 10, 700: 1}.get(weight, 0)
        _FONT_NOTE["face"] = "Helvetica Neue"
        return ImageFont.truetype(HELVETICA, size, index=index)
    except Exception:
        _FONT_NOTE["face"] = "DejaVu Sans"
        return ImageFont.truetype(str(Path(matplotlib.get_data_path()) / "fonts/ttf/DejaVuSans.ttf"), size)


def rgba(hex_color, a=255):
    r, g, b = (int(round(v * 255)) for v in to_rgb(hex_color))
    return (r, g, b, a)


def rounded_mask(size, radius, ss=4):
    w, h = size
    big = Image.new("L", (w * ss, h * ss), 0)
    ImageDraw.Draw(big).rounded_rectangle((0, 0, w * ss - 1, h * ss - 1), radius * ss, fill=255)
    return big.resize((w, h), Image.BOX)


def rounded_rect(size, radius, fill, art=None, art_offset=(0, 0)):
    """A rounded card; optional `art` is composited inside and clipped to the card's corners."""
    w, h = size
    card = Image.new("RGBA", (w, h), rgba(fill))
    if art is not None:
        layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        layer.paste(art, art_offset)
        card.alpha_composite(layer)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(card, (0, 0), rounded_mask(size, radius))
    return out


def mark_bbox(geo, px):
    """Bounding box of beam + dot in PIL coordinates (y down)."""
    lo = np.minimum(geo["beam"].min(axis=0), geo["dot"] - geo["dot_r"])
    hi = np.maximum(geo["beam"].max(axis=0), geo["dot"] + geo["dot_r"])
    return lo[0], px - hi[1], hi[0], px - lo[1]


def dashed_rect(draw, box, color, dash=10, gap=8, width=3):
    x0, y0, x1, y1 = box
    for (ax, ay, bx, by) in ((x0, y0, x1, y0), (x1, y0, x1, y1), (x1, y1, x0, y1), (x0, y1, x0, y0)):
        length = float(np.hypot(bx - ax, by - ay))
        steps = np.arange(0, length, dash + gap)
        for s in steps:
            e = min(s + dash, length)
            draw.line((ax + (bx - ax) * s / length, ay + (by - ay) * s / length,
                       ax + (bx - ax) * e / length, ay + (by - ay) * e / length), fill=color, width=width)


# ---------------------------------------------------------------- brand sheet
def brand_sheet(master, sizes):
    W, H = 2800, 1900
    sheet = Image.new("RGBA", (W, H), rgba(MIST))
    d = ImageDraw.Draw(sheet)
    ink, grey = rgba(INK), rgba("#6E6E73")

    def label(x, y, text, size=26, weight=500, fill=grey, anchor="ls"):
        d.text((x, y), text, font=font(size, weight), fill=fill, anchor=anchor)

    label(80, 110, "WiFiBar", 52, 600, ink)
    label(80 + d.textlength("WiFiBar", font=font(52, 600)) + 24, 110, "Brand kit", 52, 400, grey)

    # App icon at 1:1.
    sheet.alpha_composite(master, (80, 150))
    label(180, 1200, "App icon  ·  1024 px", 28)

    x0, cw = 1180, 755
    # Sizes on light and dark, at 1:1.
    label(x0, 235, "Sizes  ·  128, 32 and 16 px at actual size", 28)
    for i, (bgc, fg) in enumerate(((WHITE, grey), (GRAPHITE, rgba("#A1A1A6")))):
        cx = x0 + i * (cw + 30)
        sheet.alpha_composite(rounded_rect((cw, 300), 28, bgc), (cx, 260))
        mid = 260 + 128                      # icons share a centre line
        for size, left in ((128, 70), (32, 330), (16, 470)):
            sheet.alpha_composite(sizes[size], (cx + left, mid - size // 2))
            label(cx + left + size // 2, 525, str(size), 24, 500, fg, anchor="ms")
        label(cx + cw - 40, 525, "Light" if i == 0 else "Dark", 24, 500, fg, anchor="rs")

    # Mark on light and dark (the glow is clipped by the card, like the icon body clips it).
    label(x0, 640, "Mark", 28)
    light_mark, _ = render_mark(cw, 250, color=INK)
    dark_mark, _ = render_mark(cw, 250, grad=(PALE, WHITE), glow=True, rim=True, glow_alpha=0.24)
    top = (cw - 380) // 2
    for i, (bgc, mk) in enumerate(((WHITE, light_mark), (GRAPHITE, dark_mark))):
        cx = x0 + i * (cw + 30)
        sheet.alpha_composite(rounded_rect((cw, 380), 28, bgc, mk.crop((0, top, cw, top + 380))), (cx, 665))

    # Palette.
    label(x0, 1125, "Colour", 28)
    sw, gap = 240, 20
    for i, (name, hx, use) in enumerate(PALETTE):
        sx = x0 + i * (sw + gap)
        tile = rounded_rect((sw, 150), 22, hx)
        sheet.alpha_composite(tile, (sx, 1150))
        if hx in (WHITE,):
            ImageDraw.Draw(sheet).rounded_rectangle((sx, 1150, sx + sw - 1, 1299), 22, outline=rgba("#D2D2D7"), width=2)
        label(sx, 1338, name, 26, 600, ink)
        label(sx, 1372, hx.upper(), 24, 400, grey)
        words, lines, line = use.split(), [], ""
        for w_ in words:
            trial = (line + " " + w_).strip()
            if d.textlength(trial, font=font(20, 400)) > sw - 4:
                lines.append(line)
                line = w_
            else:
                line = trial
        lines.append(line)
        for k, ln in enumerate(lines):
            label(sx, 1404 + k * 26, ln, 20, 400, grey)

    # Typography: the wordmark set in SF Pro, with the mark as a lock-up.
    face = _FONT_NOTE.get("face", "SF Pro")
    note = "SF Pro (system)" if face == "SF Pro" else f"SF Pro (system), shown here in {face}"
    label(80, 1300, "Typography  ·  " + note, 28)
    word_font = font(190, 600)
    lock_mark, _ = render_mark(170, 150, color=INK)
    sheet.alpha_composite(lock_mark, (80, 1390))
    d.text((80 + 170 + 30, 1530), "WiFiBar", font=word_font, fill=ink, anchor="ls")
    label(80, 1610, "Wordmark: SF Pro Semibold, tracking default. Labels: SF Pro Regular and Medium.", 24, 400)

    # Clear space and minimum size.
    label(x0, 1515, "Clear space and minimum size", 28)
    card_h = 250
    cs_px = 250
    cs_mark, cs_geo = render_mark(cs_px, 120, color=INK)
    card = rounded_rect((cw, card_h), 28, WHITE, cs_mark, (40, (card_h - cs_px) // 2))
    cd = ImageDraw.Draw(card)
    bx0, by0, bx1, by1 = mark_bbox(cs_geo, cs_px)
    D = 2 * cs_geo["dot_r"]
    oy = (card_h - cs_px) // 2
    dashed_rect(cd, (40 + bx0 - D, oy + by0 - D, 40 + bx1 + D, oy + by1 + D), rgba(CYAN), width=3)
    dashed_rect(cd, (40 + bx0, oy + by0, 40 + bx1, oy + by1), rgba("#C7C7CC"), dash=6, gap=6, width=2)
    tx = 40 + cs_px + 20
    cd.text((tx, 105), "Keep one router-dot diameter (D)", font=font(26, 600), fill=ink, anchor="ls")
    cd.text((tx, 140), "clear on every side of the mark.", font=font(26, 400), fill=ink, anchor="ls")
    cd.text((tx, 185), "Dashed cyan line = clear-space boundary.", font=font(22, 400), fill=grey, anchor="ls")
    sheet.alpha_composite(card, (x0, 1540))

    card2 = rounded_rect((cw, card_h), 28, WHITE)
    c2 = ImageDraw.Draw(card2)
    card2.alpha_composite(sizes[16], (60, 92))
    m16, _ = render_mark(16, 16, color=INK)
    card2.alpha_composite(m16, (150, 92))
    c2.text((68, 150), "icon", font=font(20, 400), fill=grey, anchor="ms")
    c2.text((158, 150), "mark", font=font(20, 400), fill=grey, anchor="ms")
    c2.text((250, 105), "Smallest sizes: icon 16 px,", font=font(26, 600), fill=ink, anchor="ls")
    c2.text((250, 140), "mark 16 px across (shown actual size).", font=font(26, 400), fill=ink, anchor="ls")
    c2.text((250, 185), "16 and 32 px icons use a bolder small drawing.", font=font(22, 400), fill=grey, anchor="ls")
    sheet.alpha_composite(card2, (x0 + cw + 30, 1540))

    # The math, in one line.
    label(80, 1850,"The mark is plotted, not drawn:  r(θ) = exp(−(θ−θ₀)² / 2σ²),  σ = 25°, θ₀ = 45°. "
          "This is WiFiBar's own direction-sweep smoothing in polar form, aimed at the router dot on the sweep circle.",
          28, 400, ink)
    return sheet


# ---------------------------------------------------------------- Android adaptive icon
def android_layers():
    A, VIS, SAFE = 432, 288, 264   # 108 dp layers, 72 dp visible, 66 dp safe circle (xxxhdpi)
    xs = np.arange(A) + 0.5
    t = np.clip((A - xs) / A, 0, 1)[:, None] * np.ones((1, A))  # 1 at the top row
    a, b = np.array(to_rgb(INK)), np.array(to_rgb(BLUE))
    rgb = a + (b - a) * t[..., None]
    bg = Image.fromarray(np.round(np.dstack([rgb, np.ones((A, A))]) * 255).astype(np.uint8), "RGBA")
    # Size the mark so its farthest point (tail or dot edge) sits at 94 % of the safe-circle radius.
    base = mark_geometry(L=REGULAR["L"], gap=REGULAR["gap"], dot_r=REGULAR["dot_r"])
    pts = np.vstack([base["beam"], base["dot"] + base["dot_r"] * base["u"]])
    reach_unit = np.max(np.hypot(pts[:, 0], pts[:, 1]))
    scale = 0.94 * (SAFE / 2) / reach_unit
    mark_px = scale * max(base["extent"])
    fg, geo = render_mark(A, mark_px, grad=(PALE, WHITE), glow=True, glow_alpha=0.30)
    mono, _ = render_mark(A, mark_px, color=WHITE)
    # Measure where the solid mark actually ends.
    alpha = np.asarray(mono)[..., 3]
    yy, xx = np.nonzero(alpha > 8)
    reach = float(np.max(np.hypot(xx + 0.5 - A / 2, yy + 0.5 - A / 2))) if len(xx) else 0.0
    return bg, fg, mono, reach, SAFE / 2


def mask_shape(size, kind, ss=4):
    xs = (np.arange(size * ss) + 0.5) / ss - size / 2
    X, Y = np.meshgrid(xs, xs)
    h = size / 2
    if kind == "circle":
        inside = X ** 2 + Y ** 2 <= h ** 2
    elif kind == "squircle":
        inside = np.abs(X / h) ** 4 + np.abs(Y / h) ** 4 <= 1
    else:  # rounded square, corner radius 20 %
        r = 0.20 * size
        qx, qy = np.maximum(np.abs(X) - (h - r), 0), np.maximum(np.abs(Y) - (h - r), 0)
        inside = (qx ** 2 + qy ** 2 <= r ** 2) & (np.abs(X) <= h) & (np.abs(Y) <= h)
    cov = inside.reshape(size, ss, size, ss).mean(axis=(1, 3))
    return Image.fromarray(np.round(cov * 255).astype(np.uint8), "L")


def android_preview(bg, fg, mono):
    A, VIS = 432, 288
    comp = bg.copy()
    comp.alpha_composite(fg)
    crop = comp.crop(((A - VIS) // 2,) * 2 + ((A + VIS) // 2,) * 2)
    mcrop = mono.crop(((A - VIS) // 2,) * 2 + ((A + VIS) // 2,) * 2)
    W, H = 4 * VIS + 5 * 60, 2 * (VIS + 110) + 40
    out = Image.new("RGBA", (W, H), rgba(MIST))
    d = ImageDraw.Draw(out)
    out.paste(Image.new("RGBA", (W, VIS + 110), rgba(GRAPHITE)), (0, VIS + 150))
    names = ["Circle", "Squircle", "Rounded square", "Themed (monochrome)"]
    for row, (themed_bg, themed_fg, txt) in enumerate((("#D8E2FF", "#1B2E5A", "#6E6E73"),
                                                      ("#2B3A5C", "#D8E2FF", "#A1A1A6"))):
        y = 40 + row * (VIS + 150)
        for i, kind in enumerate(("circle", "squircle", "rounded", "circle")):
            x = 60 + i * (VIS + 60)
            m = mask_shape(VIS, kind)
            if i < 3:
                tile = Image.new("RGBA", (VIS, VIS), (0, 0, 0, 0))
                tile.paste(crop, (0, 0), m)
            else:
                plate = Image.new("RGBA", (VIS, VIS), rgba(themed_bg))
                glyph = Image.new("RGBA", (VIS, VIS), rgba(themed_fg))
                plate.paste(glyph, (0, 0), mcrop.split()[3])
                tile = Image.new("RGBA", (VIS, VIS), (0, 0, 0, 0))
                tile.paste(plate, (0, 0), m)
            out.alpha_composite(tile, (x, y))
            d.text((x + VIS / 2, y + VIS + 50), names[i], font=font(26, 500), fill=rgba(txt), anchor="ms")
    return out


# ---------------------------------------------------------------- main
def main():
    OUT.mkdir(parents=True, exist_ok=True)
    master = render_icon(REGULAR)
    small = render_icon(SMALL)
    save(master, OUT / "AppIcon-1024.png")

    iconset = OUT / "AppIcon.iconset"
    if iconset.exists():
        shutil.rmtree(iconset)
    iconset.mkdir()
    sizes = {}
    for name, size, kind in ICONSET:
        img = resize(small if kind == "small" else master, size)
        save(img, iconset / name)
        sizes.setdefault(size, img)
    sizes[128] = resize(master, 128)
    sizes[32] = resize(small, 32)
    sizes[16] = resize(small, 16)

    iconutil = shutil.which("iconutil") or "/usr/bin/iconutil"
    if Path(iconutil).exists():
        subprocess.run([iconutil, "-c", "icns", str(iconset), "-o", str(OUT / "AppIcon.icns")], check=True)
    else:
        print("warning: iconutil not found; AppIcon.icns was not built", file=sys.stderr)

    # Monochrome mark: black on transparent (template-ready), with one dot diameter of clear space.
    base = mark_geometry(L=REGULAR["L"], gap=REGULAR["gap"], dot_r=REGULAR["dot_r"])
    longest = max(base["extent"])
    mark_px = 1024 / (1 + 2 * (2 * REGULAR["dot_r"]) / longest)
    mono, _ = render_mark(1024, mark_px, color="#000000")
    save(mono, OUT / "mark-monochrome.png")

    save(brand_sheet(master, sizes), OUT / "brand-sheet.png")

    bg, fg, amono, reach, safe = android_layers()
    android = OUT / "android"
    save(fg, android / "ic_launcher_foreground.png")
    save(bg, android / "ic_launcher_background.png")
    save(amono, android / "ic_launcher_monochrome.png")
    save(android_preview(bg, fg, amono), android / "preview.png")
    print(f"android: solid mark reaches {reach:.1f} px from centre (safe circle radius {safe:.0f} px)")
    print(f"wordmark face: {_FONT_NOTE.get('face')}")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
