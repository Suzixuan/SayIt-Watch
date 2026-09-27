"""Deterministic 480x480 concept renders for the SayIt computer picker.

This candidate changes information hierarchy only. It does not imply that the
runtime already provides friendly names or stable device IDs.
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
SIZE = 480
SCALE = SIZE / 226.0

BG = (0x12, 0x16, 0x1D)
SURFACE = (0x20, 0x25, 0x2E)
SURFACE_2 = (0x2A, 0x30, 0x3A)
BLUE = (0x24, 0x7C, 0xF0)
BLUE_DARK = (0x18, 0x58, 0xB5)
GREEN = (0x4C, 0xD9, 0x96)
AMBER = (0xF2, 0xB8, 0x4B)
WHITE = (0xF7, 0xF9, 0xFC)
MUTED = (0xA8, 0xB2, 0xC0)
DIM = (0x78, 0x83, 0x92)

FONT_REGULAR = "C:/Windows/Fonts/msyh.ttc"
FONT_BOLD = "C:/Windows/Fonts/msyhbd.ttc"


def dp(value: float) -> int:
    return int(round(value * SCALE))


def font(size_dp: float, bold: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(FONT_BOLD if bold else FONT_REGULAR, dp(size_dp))


def text(draw, xy, value, size, fill=WHITE, bold=False, anchor=None):
    draw.text(xy, value, font=font(size, bold), fill=fill, anchor=anchor)


def close_button(draw):
    # The only position with the widest round-screen safe width: bottom centre.
    cx, cy, radius = 240, 432, 20
    draw.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=SURFACE)
    draw.line((cx - 6, cy - 6, cx + 6, cy + 6), fill=MUTED, width=3)
    draw.line((cx + 6, cy - 6, cx - 6, cy + 6), fill=MUTED, width=3)


def computer_icon(draw, box, laptop=False, fill=WHITE):
    x0, y0, x1, y1 = box
    w = max(2, int((x1 - x0) * 0.08))
    if laptop:
        draw.rounded_rectangle((x0 + 6, y0 + 5, x1 - 6, y1 - 9), radius=4, outline=fill, width=w)
        draw.line((x0 + 1, y1 - 6, x1 - 1, y1 - 6), fill=fill, width=w)
        draw.line((x0 + 8, y1 - 2, x1 - 8, y1 - 2), fill=fill, width=w)
    else:
        draw.rounded_rectangle((x0 + 4, y0 + 3, x1 - 4, y1 - 9), radius=4, outline=fill, width=w)
        cx = (x0 + x1) / 2
        draw.line((cx, y1 - 9, cx, y1 - 3), fill=fill, width=w)
        draw.line((cx - 10, y1 - 2, cx + 10, y1 - 2), fill=fill, width=w)


def spinner(draw, cx, cy, radius=9):
    for i, shade in enumerate((BLUE, BLUE, BLUE_DARK, SURFACE_2, SURFACE_2, SURFACE_2)):
        start = -90 + i * 60
        draw.arc((cx - radius, cy - radius, cx + radius, cy + radius), start, start + 38, fill=shade, width=4)


def device_card(draw, y, name, meta, *, current=False, laptop=False, disabled=False, favorite=False):
    x0, x1, height = 26, 454, 98
    color = BLUE if current else SURFACE
    if disabled:
        color = (0x1B, 0x20, 0x28)
    draw.rounded_rectangle((x0, y, x1, y + height), radius=27, fill=color)

    icon_fill = WHITE if not disabled else DIM
    draw.ellipse((47, y + 23, 101, y + 77), fill=BLUE_DARK if current else SURFACE_2)
    computer_icon(draw, (60, y + 36, 88, y + 66), laptop=laptop, fill=icon_fill)
    text(draw, (119, y + 27), name, 15, icon_fill, bold=True)
    text(draw, (119, y + 61), meta, 9, WHITE if current else (DIM if disabled else MUTED))

    if favorite:
        text(draw, (406, y + 31), "★", 16, AMBER, anchor="mm")
    elif current:
        draw.ellipse((389, y + 29, 423, y + 63), fill=WHITE)
        draw.line((398, y + 46, 405, y + 53), fill=BLUE, width=4)
        draw.line((405, y + 53, 415, y + 40), fill=BLUE, width=4)
    else:
        text(draw, (405, y + 49), "›", 22, DIM if disabled else MUTED, anchor="mm")


def edge_refresh(draw, label="重新搜索"):
    x0, y0, x1, y1 = 112, 344, 368, 404
    draw.rounded_rectangle((x0, y0, x1, y1), radius=30, fill=BLUE)
    draw.arc((190, 360, 218, 388), -70, 250, fill=WHITE, width=4)
    draw.polygon(((214, 357), (220, 367), (208, 367)), fill=WHITE)
    text(draw, (280, 374), label, 12, WHITE, bold=True, anchor="mm")


def render_picker():
    image = Image.new("RGB", (SIZE, SIZE), BG)
    draw = ImageDraw.Draw(image)
    text(draw, (240, 49), "选择电脑", 17, WHITE, bold=True, anchor="mm")
    text(draw, (240, 79), "2 台可用", 9, MUTED, anchor="mm")
    device_card(draw, 102, "书房台式机", "当前 · 192.168.12.142", current=True)
    device_card(draw, 216, "工作笔记本", "在线 · 192.168.12.153", laptop=True, favorite=True)
    edge_refresh(draw)
    close_button(draw)
    image.save(HERE / "01-device-picker.png")


def render_searching():
    image = Image.new("RGB", (SIZE, SIZE), BG)
    draw = ImageDraw.Draw(image)
    text(draw, (240, 49), "选择电脑", 17, WHITE, bold=True, anchor="mm")
    spinner(draw, 168, 79)
    text(draw, (250, 79), "仍在搜索 · 已找到 2 台", 9, MUTED, anchor="mm")
    device_card(draw, 102, "书房台式机", "当前 · 192.168.12.142", current=True)
    device_card(draw, 216, "工作笔记本", "已验证 · 现在可选择", laptop=True)
    edge_refresh(draw, "重新开始")
    close_button(draw)
    image.save(HERE / "02-searching-with-results.png")


def render_fallback():
    image = Image.new("RGB", (SIZE, SIZE), BG)
    draw = ImageDraw.Draw(image)
    text(draw, (240, 49), "选择电脑", 17, WHITE, bold=True, anchor="mm")
    text(draw, (240, 79), "设备还没有昵称", 9, AMBER, anchor="mm")
    device_card(draw, 102, "电脑 · 142", "当前 · 192.168.12.142", current=True)
    device_card(draw, 216, "电脑 · 153", "在线 · 192.168.12.153", laptop=True)
    edge_refresh(draw)
    close_button(draw)
    image.save(HERE / "03-unnamed-fallback.png")


def render_profile():
    image = Image.new("RGB", (SIZE, SIZE), BG)
    draw = ImageDraw.Draw(image)
    text(draw, (240, 46), "电脑资料", 17, WHITE, bold=True, anchor="mm")
    close_button(draw)
    draw.ellipse((191, 76, 289, 174), fill=BLUE_DARK)
    computer_icon(draw, (215, 101, 265, 151), laptop=True)
    text(draw, (240, 199), "工作笔记本", 16, WHITE, bold=True, anchor="mm")
    text(draw, (240, 229), "192.168.12.153:18099", 9, MUTED, anchor="mm")

    draw.rounded_rectangle((47, 253, 433, 315), radius=22, fill=SURFACE)
    text(draw, (74, 284), "名称", 10, MUTED, anchor="lm")
    text(draw, (401, 284), "在电脑端修改  ›", 10, WHITE, anchor="rm")
    draw.rounded_rectangle((47, 325, 238, 383), radius=22, fill=SURFACE)
    text(draw, (69, 354), "★", 13, AMBER, anchor="lm")
    text(draw, (98, 354), "设为常用", 10, WHITE, anchor="lm")
    draw.rounded_rectangle((248, 325, 433, 383), radius=22, fill=SURFACE)
    draw.ellipse((271, 342, 295, 366), fill=BLUE)
    text(draw, (307, 354), "识别颜色", 10, WHITE, anchor="lm")
    text(draw, (240, 421), "完整地址只在这里显示", 9, DIM, anchor="mm")
    image.save(HERE / "04-device-profile.png")


def render_comparison():
    before = Image.open(HERE / "parent-searching-dev8.png").convert("RGB")
    after = Image.open(HERE / "02-searching-with-results.png").convert("RGB")
    canvas = Image.new("RGB", (1000, 540), (0x0C, 0x0F, 0x14))
    canvas.paste(before, (10, 50))
    canvas.paste(after, (510, 50))
    draw = ImageDraw.Draw(canvas)
    text(draw, (250, 24), "dev.8：要等搜索结束", 12, MUTED, bold=True, anchor="mm")
    text(draw, (750, 24), "候选：认证后立即可选", 12, WHITE, bold=True, anchor="mm")
    canvas.save(HERE / "05-before-after.png")


if __name__ == "__main__":
    render_picker()
    render_searching()
    render_fallback()
    render_profile()
    render_comparison()
    print("wrote 5 preview files")
