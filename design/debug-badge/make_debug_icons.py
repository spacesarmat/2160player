"""
Иконки отладочной сборки (tv.p2160.player.debug) с полосой «ТЕСТ», чтобы на телефоне и ТВ
отличать её от обычной версии. Кладёт файлы в app/src/debug/res — они перекрывают main только в debug.

Запуск из корня проекта:  python design/debug-badge/make_debug_icons.py
"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MAIN = os.path.join(ROOT, "app", "src", "main", "res")
DEBUG = os.path.join(ROOT, "app", "src", "debug", "res")
FONT = r"C:\Windows\Fonts\arialbd.ttf"
TEXT = "ТЕСТ"
RED = (229, 57, 53, 255)
WHITE = (255, 255, 255, 255)


def font_for(width, height):
    size = height
    while size > 4:
        f = ImageFont.truetype(FONT, size)
        l, t, r, b = f.getbbox(TEXT)
        if r - l <= width and b - t <= height:
            return f
        size -= 1
    return ImageFont.truetype(FONT, 4)


def badge(img, box, fill=RED, text_fill=WHITE, radius_ratio=0.25):
    """Полоса с текстом в прямоугольнике box = (x0, y0, x1, y1)."""
    d = ImageDraw.Draw(img)
    x0, y0, x1, y1 = box
    h = y1 - y0
    d.rounded_rectangle(box, radius=int(h * radius_ratio), fill=fill)
    f = font_for(int((x1 - x0) * 0.82), int(h * 0.72))
    l, t, r, b = f.getbbox(TEXT)
    d.text((x0 + (x1 - x0 - (r - l)) / 2 - l, y0 + (h - (b - t)) / 2 - t), TEXT, font=f, fill=text_fill)


def save(img, rel):
    path = os.path.join(DEBUG, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


for density in ["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]:
    folder = "mipmap-" + density
    # Адаптивная иконка: передний план 108 dp, видимая зона — центральные 72 dp (с 18 до 90).
    fg = Image.open(os.path.join(MAIN, folder, "ic_launcher_foreground.png")).convert("RGBA")
    w, h = fg.size
    badge(fg, (int(w * 0.22), int(h * 0.64), int(w * 0.78), int(h * 0.76)))
    save(fg, os.path.join(folder, "ic_launcher_foreground.png"))
    # Монохромная (тематические иконки Android 13+): та же полоса одним цветом, текст вырезан.
    mono = Image.open(os.path.join(MAIN, folder, "ic_launcher_monochrome.png")).convert("RGBA")
    w, h = mono.size
    layer = Image.new("RGBA", mono.size, (0, 0, 0, 0))
    badge(layer, (int(w * 0.22), int(h * 0.64), int(w * 0.78), int(h * 0.76)), fill=WHITE, text_fill=(0, 0, 0, 255))
    alpha = layer.split()[3].point(lambda a: a)
    r, g, b, _ = layer.split()
    cut = Image.merge("RGBA", (r, g, b, alpha))
    mono.alpha_composite(cut)
    # текст — прозрачный (монохром использует только альфу)
    px = mono.load()
    lp = layer.load()
    for y in range(h):
        for x in range(w):
            if lp[x, y][3] > 0 and lp[x, y][0] < 128:
                px[x, y] = (0, 0, 0, 0)
    save(mono, os.path.join(folder, "ic_launcher_monochrome.png"))
    # Обычные иконки (Android 7): полоса внизу.
    for name in ["ic_launcher.png", "ic_launcher_round.png"]:
        icon = Image.open(os.path.join(MAIN, folder, name)).convert("RGBA")
        w, h = icon.size
        badge(icon, (int(w * 0.12), int(h * 0.66), int(w * 0.88), int(h * 0.86)))
        save(icon, os.path.join(folder, name))

# Баннер Android TV (320×180 dp): полоса в правом нижнем углу.
for folder in ["drawable-mdpi", "drawable-xhdpi"]:
    banner = Image.open(os.path.join(MAIN, folder, "tv_banner.png")).convert("RGBA")
    w, h = banner.size
    badge(banner, (int(w * 0.70), int(h * 0.74), int(w * 0.96), int(h * 0.93)))
    save(banner, os.path.join(folder, "tv_banner.png"))

print("ok:", DEBUG)
