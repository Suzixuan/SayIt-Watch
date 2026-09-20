"""Render the Ready alignment study into this staging directory only."""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
PARENT = HERE.parent / "0.2.0-dev.3-final" / "ready.png"
FONT = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 16)

ready = Image.open(PARENT).convert("RGB")
draw = ImageDraw.Draw(ready)
label = "已连接电脑"
box = draw.textbbox((0, 0), label, font=FONT)
draw.text((240 - (box[2] - box[0]) / 2, 342 - (box[3] - box[1]) / 2), label, font=FONT, fill="#87919F")
ready.save(HERE / "01-ready.png")
