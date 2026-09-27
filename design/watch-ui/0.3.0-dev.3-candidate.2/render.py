"""Deterministic previews for the configurable low-power recording UI."""

from pathlib import Path
from math import cos, pi, sin

from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
SIZE = 480
BLUE = (0x24, 0x7C, 0xF0)
WHITE = (0xF5, 0xF7, 0xFA)
MUTED = (0x94, 0x9E, 0xAC)
DIM = (0x4B, 0x55, 0x62)
FACE = (0x03, 0x05, 0x08)
PANEL = (0x15, 0x1A, 0x22)
CARD = (0x26, 0x2D, 0x38)
LIGHT_FACE = (0xF6, 0xF7, 0xF9)
LIGHT_MUTED = (0x9A, 0xA3, 0xAE)
LIGHT_TEXT = (0x1C, 0x1E, 0x22)
FONT_REGULAR = "C:/Windows/Fonts/segoeui.ttf"
FONT_BOLD = "C:/Windows/Fonts/segoeuib.ttf"
FONT_CJK = "C:/Windows/Fonts/msyh.ttc"


def font(size: int, *, bold: bool = False, cjk: bool = False):
    path = FONT_CJK if cjk else (FONT_BOLD if bold else FONT_REGULAR)
    return ImageFont.truetype(path, size)


def centered(draw, xy, value, size, fill=WHITE, *, bold=False, cjk=False):
    draw.text(xy, value, font=font(size, bold=bold, cjk=cjk), fill=fill, anchor="mm")


def dial_ticks(draw, *, low_power: bool):
    center = (240, 240)
    radius = 239
    count = 24 if low_power else 60
    for i in range(count):
        angle = (i * (360 / count) - 90) * pi / 180
        major = i % (count // 4) == 0
        outer = radius - 2
        inner = radius - (31 if major else (13 if low_power else 11))
        start = (center[0] + cos(angle) * inner, center[1] + sin(angle) * inner)
        end = (center[0] + cos(angle) * outer, center[1] + sin(angle) * outer)
        draw.line((start, end), fill=BLUE if major else (DIM if low_power else LIGHT_MUTED), width=7 if major else 2)


def waveform(draw, *, static: bool, cy=238):
    heights = [18, 32, 45, 60, 74, 60, 45, 32, 18] if static else [22, 32, 39, 46, 51, 55, 58, 55, 49, 39, 25]
    gap = 28 if static else 30
    x0 = 240 - gap * (len(heights) - 1) / 2
    for index, height in enumerate(heights):
        x = x0 + index * gap
        draw.rounded_rectangle((x - 5, cy - height / 2, x + 5, cy + height / 2), radius=5, fill=BLUE)


def recording(timer: str, *, low_power: bool):
    image = Image.new("RGB", (SIZE, SIZE), (0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.ellipse((1, 1, 479, 479), fill=FACE if low_power else LIGHT_FACE)
    dial_ticks(draw, low_power=low_power)
    if low_power:
        draw.ellipse((154, 111, 166, 123), fill=BLUE)
        centered(draw, (253, 117), "RECORDING", 17, MUTED, bold=True)
        waveform(draw, static=True)
        centered(draw, (240, 329), timer, 43, WHITE, bold=True)
        centered(draw, (240, 397), "轻触波形停止", 14, MUTED, cjk=True)
        centered(draw, (240, 425), "取消并丢弃", 11, DIM, cjk=True)
    else:
        centered(draw, (240, 122), "RECORDING", 17, LIGHT_MUTED, bold=True)
        waveform(draw, static=False)
        centered(draw, (240, 351), timer, 43, LIGHT_TEXT, bold=True)
        centered(draw, (240, 421), "取消", 14, LIGHT_MUTED, cjk=True)
    return image


def field_card(draw, y, label, value):
    draw.rounded_rectangle((42, y, 438, y + 64), radius=20, fill=CARD)
    draw.text((62, y + 13), label, font=font(11, cjk=True), fill=MUTED)
    draw.text((62, y + 38), value, font=font(15, cjk=True), fill=WHITE)
    draw.text((414, y + 32), "›", font=font(24), fill=MUTED, anchor="mm")


def config_preview():
    image = Image.new("RGB", (SIZE, SIZE), PANEL)
    draw = ImageDraw.Draw(image)
    centered(draw, (240, 48), "连接设置", 22, WHITE, bold=True, cjk=True)
    field_card(draw, 78, "访问令牌（64 位）", "A1B2••••••••7890")
    field_card(draw, 154, "低功耗界面（秒）", "10")
    centered(draw, (240, 236), "录音超过该时长后切换为黑底静态界面", 11, MUTED, cjk=True)
    centered(draw, (240, 254), "可设置 1–180 秒", 10, DIM, cjk=True)
    field_card(draw, 274, "手动设置地址（可选）", "显示")
    draw.rounded_rectangle((92, 360, 388, 418), radius=29, fill=BLUE)
    centered(draw, (240, 389), "SAVE & APPLY", 15, WHITE, bold=True)
    return image


def side_by_side(left, right, left_label, right_label):
    canvas = Image.new("RGB", (1000, 540), (0x0C, 0x0F, 0x14))
    canvas.paste(left, (10, 50))
    canvas.paste(right, (510, 50))
    draw = ImageDraw.Draw(canvas)
    centered(draw, (250, 24), left_label, 18, MUTED, bold=True, cjk=True)
    centered(draw, (750, 24), right_label, 18, WHITE, bold=True, cjk=True)
    return canvas


if __name__ == "__main__":
    config_preview().save(HERE / "01-connection-setting.png")
    recording("00:10", low_power=True).save(HERE / "02-low-power-recording.png")
    side_by_side(
        recording("00:09", low_power=False),
        recording("00:10", low_power=True),
        "阈值前：00:09 · 亮底动画",
        "达到设置值：00:10 · 黑底静态",
    ).save(HERE / "03-configurable-transition.png")
    print("wrote 3 preview files")
