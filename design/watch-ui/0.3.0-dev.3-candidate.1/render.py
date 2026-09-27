"""Deterministic low-power recording UI concept for the 480 px Galaxy Watch canvas."""

from pathlib import Path
from math import cos, pi, sin

from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
SIZE = 480
BLUE = (0x24, 0x7C, 0xF0)
BLUE_DIM = (0x16, 0x4A, 0x8B)
WHITE = (0xF5, 0xF7, 0xFA)
MUTED = (0x94, 0x9E, 0xAC)
DIM = (0x4B, 0x55, 0x62)
FACE = (0x03, 0x05, 0x08)
EDGE = (0x18, 0x1D, 0x25)
FONT_REGULAR = "C:/Windows/Fonts/segoeui.ttf"
FONT_BOLD = "C:/Windows/Fonts/segoeuib.ttf"
FONT_CJK = "C:/Windows/Fonts/msyh.ttc"


def font(size: int, *, bold: bool = False, cjk: bool = False):
    path = FONT_CJK if cjk else (FONT_BOLD if bold else FONT_REGULAR)
    return ImageFont.truetype(path, size)


def centered(draw, xy, value, size, fill=WHITE, *, bold=False, cjk=False):
    draw.text(xy, value, font=font(size, bold=bold, cjk=cjk), fill=fill, anchor="mm")


def draw_ticks(draw):
    center = (240, 240)
    radius = 239
    # Fewer, dimmer marks retain the SayIt dial language without lighting the whole rim.
    for i in range(24):
        angle = (i * 15 - 90) * pi / 180
        major = i % 6 == 0
        outer = radius - 2
        inner = radius - (31 if major else 13)
        start = (center[0] + cos(angle) * inner, center[1] + sin(angle) * inner)
        end = (center[0] + cos(angle) * outer, center[1] + sin(angle) * outer)
        draw.line((start, end), fill=BLUE if major else DIM, width=7 if major else 2)


def draw_static_wave(draw, cy=238):
    heights = [18, 32, 45, 60, 74, 60, 45, 32, 18]
    gap = 28
    x0 = 240 - gap * (len(heights) - 1) / 2
    for index, height in enumerate(heights):
        x = x0 + index * gap
        draw.rounded_rectangle((x - 5, cy - height / 2, x + 5, cy + height / 2), radius=5, fill=BLUE)


def render_candidate(timer="02:37"):
    image = Image.new("RGB", (SIZE, SIZE), (0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.ellipse((1, 1, 479, 479), fill=FACE, outline=EDGE, width=2)
    draw_ticks(draw)

    draw.ellipse((154, 111, 166, 123), fill=BLUE)
    centered(draw, (253, 117), "RECORDING", 17, MUTED, bold=True)
    draw_static_wave(draw)
    centered(draw, (240, 329), timer, 43, WHITE, bold=True)
    centered(draw, (240, 397), "轻触波形停止", 14, MUTED, cjk=True)
    centered(draw, (240, 425), "取消并丢弃", 11, DIM, cjk=True)
    return image


def render_tick_pair():
    canvas = Image.new("RGB", (1000, 540), (0x0C, 0x0F, 0x14))
    first = render_candidate("02:37")
    second = render_candidate("02:38")
    canvas.paste(first, (10, 50))
    canvas.paste(second, (510, 50))
    draw = ImageDraw.Draw(canvas)
    centered(draw, (250, 24), "静态波形 · 02:37", 18, MUTED, bold=True, cjk=True)
    centered(draw, (750, 24), "一秒后 · 02:38", 18, WHITE, bold=True, cjk=True)
    return canvas


def render_comparison():
    parent_path = REPO / "docs" / "images" / "readme" / "watch" / "recording.png"
    before = Image.open(parent_path).convert("RGB")
    after = render_candidate()
    canvas = Image.new("RGB", (1000, 540), (0x0C, 0x0F, 0x14))
    canvas.paste(before, (10, 50))
    canvas.paste(after, (510, 50))
    draw = ImageDraw.Draw(canvas)
    centered(draw, (250, 24), "当前：亮底 + 持续动画", 18, MUTED, bold=True, cjk=True)
    centered(draw, (750, 24), "候选：黑底 + 静态波形", 18, WHITE, bold=True, cjk=True)
    return canvas


if __name__ == "__main__":
    render_candidate().save(HERE / "01-low-power-recording.png")
    render_tick_pair().save(HERE / "02-one-hz-timer.png")
    render_comparison().save(HERE / "03-before-after.png")
    print("wrote 3 preview files")
