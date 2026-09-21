"""1C-D-04@R7 — 480x480 round-screen previews for the explicit switch page.

This is a deterministic METRICS RENDER, not a runtime screenshot: it draws the switch
page from the exact dp values in `RecordingScreen.kt` (`ComputerSwitchDialog`) and the
exact strings in `strings.R7.xml`, so a layout regression that changes those values is
visible here. It cannot prove the runtime Compose layout; the real-device screenshot
remains the PM's acceptance step.

Run from this directory:  python render.py
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent

# Galaxy Watch 7 (SM-L310): 480x480 px at density 340 -> the logical dp canvas is 226dp.
# These previews map 1dp -> 480/226 px, the same nominal mapping the earlier candidates use.
SIZE = 480
SCALE = SIZE / 226.0

FACE = (0x15, 0x1A, 0x22)        # dialog background (ReadSettingsMenu `face`)
PANEL = (0x20, 0x25, 0x2E)       # PanelSurface
CARD = (0x25, 0x2A, 0x34)        # FieldSurface
ACCENT = (0x19, 0x76, 0xE9)      # SayItBlue
MUTED = (0xB5, 0xBF, 0xCC)       # MutedText
WARN = (0xE0, 0xA2, 0x4B)        # WarningRed
WHITE = (0xFF, 0xFF, 0xFF)

FONT_REGULAR = "C:/Windows/Fonts/msyh.ttc"
FONT_BOLD = "C:/Windows/Fonts/msyhbd.ttc"


def dp(value: float) -> int:
    """dp -> preview pixels, the same nominal mapping the dial uses."""
    return int(round(value * SCALE))


def font(size_dp: float, bold: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(FONT_BOLD if bold else FONT_REGULAR, dp(size_dp))


def center_text(draw: ImageDraw.ImageDraw, y: int, text: str, fnt, fill) -> int:
    """Draws centred text and returns its height."""
    left, top, right, bottom = draw.textbbox((0, 0), text, font=fnt)
    draw.text(((SIZE - (right - left)) / 2 - left, y), text, font=fnt, fill=fill)
    return bottom - top


def pill(draw: ImageDraw.ImageDraw, y: int, label: str, background, height_dp: float = 52.0,
         width_dp: float = 186.0) -> int:
    """One `PillAction`: a rounded capsule with a centred bold label."""
    height = dp(height_dp)
    width = dp(width_dp)
    x0 = (SIZE - width) // 2
    draw.rounded_rectangle((x0, y, x0 + width, y + height), radius=height // 2, fill=background)
    fnt = font(14, bold=True)
    left, top, right, bottom = draw.textbbox((0, 0), label, font=fnt)
    draw.text(((SIZE - (right - left)) / 2 - left, y + (height - (bottom - top)) / 2 - top),
              label, font=fnt, fill=WHITE)
    return y + height


def row(draw: ImageDraw.ImageDraw, y: int, label: str, address: str, current: bool) -> int:
    """One `SwitchEntry` row: short state label above the real IP:port."""
    height = dp(40)
    x0, x1 = dp(14), SIZE - dp(14)
    draw.rounded_rectangle((x0, y, x1, y + height), radius=dp(12),
                           fill=ACCENT if current else CARD)
    label_font = font(9)
    address_font = font(13)
    label_color = WHITE if current else MUTED

    left, top, right, bottom = draw.textbbox((0, 0), label, font=label_font)
    draw.text(((SIZE - (right - left)) / 2 - left, y + dp(4) - top), label, font=label_font,
              fill=label_color)
    left, top, right, bottom = draw.textbbox((0, 0), address, font=address_font)
    draw.text(((SIZE - (right - left)) / 2 - left, y + dp(17) - top), address, font=address_font,
              fill=WHITE)
    return y + height


def header(draw: ImageDraw.ImageDraw, y: int, address: str | None, hint: str) -> int:
    """`当前电脑` + the real IP:port, or `未连接` when nothing is usable."""
    y += center_text(draw, y, "当前电脑", font(9), MUTED)
    y += dp(3)
    if address is None:
        y += center_text(draw, y, "未连接", font(15, bold=True), WARN)
    else:
        y += center_text(draw, y, address, font(15, bold=True), WHITE)
    y += dp(10)
    y += center_text(draw, y, hint, font(9), MUTED)
    return y + dp(8)


def render(name: str, address: str | None, hint: str, hint_color, rows, buttons, current_index=None):
    image = Image.new("RGB", (SIZE, SIZE), FACE)
    draw = ImageDraw.Draw(image)

    # The dialog column is the full safe width of the round screen.
    y = dp(20)
    y = header(draw, y, address, hint)
    if hint_color is not None:
        # Re-draw the hint in its state colour (searching / no candidate).
        draw.rectangle((0, y - dp(18), SIZE, y - dp(6)), fill=FACE)
        center_text(draw, y - dp(18), hint, font(9), hint_color)

    entries = [(label, addr, idx == current_index) for idx, (label, addr) in enumerate(rows)]
    for label, addr, is_current in entries:
        y = row(draw, y, label, addr, is_current)
        y += dp(6)

    y = buttons(draw, y + dp(4))
    image.save(HERE / name)
    print(f"wrote {name}")


def render_two_candidates():
    """Two authenticated computers, one in use. The browse offers BOTH; nothing is adopted."""
    render(
        "01-switch-two-computers.png",
        address="192.168.12.142:18099",
        hint="点选要使用的那一台",
        hint_color=None,
        rows=[("当前使用", "192.168.12.142:18099"), ("可选电脑", "192.168.12.153:18099")],
        buttons=lambda draw, y: pill(draw, pill(draw, y, "重新搜索", ACCENT) + dp(8), "返回", CARD),
        current_index=0,
    )


def render_not_found():
    """No candidate answered AND nothing is in use: the failure state must say so."""
    render(
        "02-switch-not-found.png",
        address=None,
        hint="未找到电脑",
        hint_color=WARN,
        rows=[],
        buttons=lambda draw, y: pill(draw, pill(draw, y, "重新搜索", ACCENT) + dp(8), "返回", CARD),
    )


def render_searching():
    """The bounded browse is running: the current computer stays pinned and usable."""
    render(
        "03-switch-searching.png",
        address="192.168.12.142:18099",
        hint="正在搜索…",
        hint_color=ACCENT,
        rows=[("当前使用", "192.168.12.142:18099")],
        buttons=lambda draw, y: pill(draw, pill(draw, y, "重新搜索", ACCENT) + dp(8), "返回", CARD),
        current_index=0,
    )


def render_no_candidate():
    """Nothing new answered: the page says so and keeps the computer in use."""
    render(
        "04-switch-no-candidate.png",
        address="192.168.12.142:18099",
        hint="正在连接…",
        hint_color=WARN,
        rows=[("当前使用", "192.168.12.142:18099")],
        buttons=lambda draw, y: pill(draw, pill(draw, y, "重新搜索", ACCENT) + dp(8), "返回", CARD),
        current_index=0,
    )


def render_disconnected():
    """No usable current target: the current-computer line says `未连接`, never a stale IP."""
    render(
        "05-switch-not-connected.png",
        address=None,
        hint="点选要使用的那一台",
        hint_color=None,
        rows=[("可选电脑", "192.168.12.153:18099")],
        buttons=lambda draw, y: pill(draw, pill(draw, y, "重新搜索", ACCENT) + dp(8), "返回", CARD),
    )


def render_ready_neutral():
    """The unchanged Ready dial, for comparison against the frozen parent."""
    image = Image.open(HERE / "parent-ready.png").convert("RGB")
    draw = ImageDraw.Draw(image)
    # The Ready dial is unchanged by R7; this copy exists so the comparison is in-tree.
    draw.text((dp(12), SIZE - dp(16)), "R7: Ready dial unchanged", font=font(7), fill=MUTED)
    image.save(HERE / "06-ready-unchanged.png")
    print("wrote 06-ready-unchanged.png")


if __name__ == "__main__":
    render_two_candidates()
    render_not_found()
    render_searching()
    render_no_candidate()
    render_disconnected()
    render_ready_neutral()
