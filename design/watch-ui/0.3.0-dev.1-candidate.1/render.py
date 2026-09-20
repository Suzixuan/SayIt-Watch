"""Deterministic 480 px placement study; runtime Compose must be checked on Watch."""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
PARENT = HERE.parent / "0.2.0-dev.3-final" / "ready.png"
FONT_PATH = "C:/Windows/Fonts/msyh.ttc"


def font(size: int):
    return ImageFont.truetype(FONT_PATH, size)


def centered(draw, xy, value, face, fill):
    box = draw.textbbox((0, 0), value, font=face)
    draw.text((xy[0] - (box[2] - box[0]) / 2, xy[1] - (box[3] - box[1]) / 2), value, font=face, fill=fill)


ready = Image.open(PARENT).convert("RGBA")
draw = ImageDraw.Draw(ready)
centered(draw, (240, 342), "已连接电脑", font(16), "#87919F")
ready.convert("RGB").save(HERE / "01-ready.png")

menu = ready.copy()
shade = Image.new("RGBA", menu.size, (0, 0, 0, 125))
menu.alpha_composite(shade)
draw = ImageDraw.Draw(menu)
draw.rounded_rectangle((72, 96, 408, 384), radius=29, fill="#22262E")
centered(draw, (240, 119), "设置", font(23), "#FFFFFF")
draw.rounded_rectangle((92, 147, 388, 216), radius=28, fill="#1976E9")
centered(draw, (240, 179), "切换电脑", font(23), "#FFFFFF")
draw.rounded_rectangle((92, 232, 388, 301), radius=28, fill="#363B44")
centered(draw, (240, 264), "连接设置", font(23), "#FFFFFF")
centered(draw, (240, 343), "取消", font(18), "#A7AFBC")
menu.convert("RGB").save(HERE / "02-settings-menu.png")
