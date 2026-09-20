"""Pixel-accurate study of a 2 dp left optical offset for the Ready status."""

from pathlib import Path
from PIL import Image, ImageDraw


here = Path(__file__).resolve().parent
image = Image.open(here / "parent-ready.png").convert("RGB")
status = image.crop((180, 326, 301, 357))
ImageDraw.Draw(image).rectangle((180, 326, 300, 356), fill=image.getpixel((180, 335)))
image.paste(status, (176, 326))
image.save(here / "ready-status-centered.png")
