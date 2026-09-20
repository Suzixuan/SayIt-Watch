"""Render the compact round Settings study into staging, never a frozen version."""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
FONT_PATH = "C:/Windows/Fonts/msyh.ttc"
BG = "#151A22"
CARD = "#262D38"
WHITE = "#F7F9FC"
MUTED = "#A4ADBA"
BLUE = "#3488F5"


def face(size):
    return ImageFont.truetype(FONT_PATH, size)


def centered(draw, center_x, top, value, font, color):
    bounds = draw.textbbox((0, 0), value, font=font)
    draw.text((center_x - (bounds[2] - bounds[0]) / 2, top), value, font=font, fill=color)


image = Image.new("RGB", (480, 480), "#000000")
draw = ImageDraw.Draw(image)
draw.ellipse((0, 0, 479, 479), fill=BG)

centered(draw, 240, 58, "设置", face(28), WHITE)
centered(draw, 240, 97, "电脑连接", face(17), MUTED)
draw.line((359, 71, 379, 91), fill=MUTED, width=3)
draw.line((379, 71, 359, 91), fill=MUTED, width=3)


def row(y0, title, detail, primary):
    y1 = y0 + 108
    draw.rounded_rectangle((77, y0, 403, y1), radius=27, fill=CARD)
    icon_color = BLUE if primary else MUTED
    draw.ellipse((96, y0 + 30, 144, y0 + 78), fill="#1B2B43" if primary else "#313946")
    if primary:
        draw.line((109, y0 + 45, 133, y0 + 45), fill=icon_color, width=3)
        draw.polygon([(133, y0 + 40), (140, y0 + 45), (133, y0 + 50)], fill=icon_color)
        draw.line((131, y0 + 63, 107, y0 + 63), fill=icon_color, width=3)
        draw.polygon([(107, y0 + 58), (100, y0 + 63), (107, y0 + 68)], fill=icon_color)
    else:
        for yy, knob in ((y0 + 45, 119), (y0 + 62, 130)):
            draw.line((107, yy, 135, yy), fill=icon_color, width=3)
            draw.ellipse((knob - 4, yy - 4, knob + 4, yy + 4), fill=CARD, outline=icon_color, width=2)
    draw.text((156, y0 + 20), title, font=face(24), fill=WHITE)
    draw.text((156, y0 + 58), detail, font=face(15), fill=MUTED)
    draw.line((372, y0 + 45, 380, y0 + 54), fill=MUTED, width=3)
    draw.line((380, y0 + 54, 372, y0 + 63), fill=MUTED, width=3)


row(142, "切换电脑", "搜索局域网中的电脑", True)
row(264, "连接设置", "Token 和手动地址", False)
image.save(HERE / "settings-menu.png")
