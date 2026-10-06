# -*- coding: utf-8 -*-
"""Android PNG set for the recommended One UI concept."""
import os, sys
from PIL import Image, ImageDraw
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import oneui
from render import shot

KEY = sys.argv[1] if len(sys.argv) > 1 else 'A'
OUT = os.path.join(HERE, 'final')
SRC = os.path.join(OUT, '_src')
os.makedirs(SRC, exist_ok=True)
DENS = [('mdpi', 1.0), ('hdpi', 1.5), ('xhdpi', 2.0), ('xxhdpi', 3.0), ('xxxhdpi', 4.0)]


def raster(svg_text, name, w, h, transparent=True):
    p = os.path.join(SRC, name + '.svg')
    with open(p, 'w', encoding='utf-8') as f: f.write(svg_text)
    html = os.path.join(SRC, name + '.html')
    with open(html, 'w', encoding='utf-8') as f:
        f.write('<!doctype html><html><body style="margin:0;background:transparent;overflow:hidden">'
                '<img src="%s.svg" style="display:block;width:%dpx;height:%dpx"></body></html>' % (name, w, h))
    png = os.path.join(SRC, name + '.png')
    shot(html, png, w, h, transparent=transparent)
    im = Image.open(png).convert('RGBA')
    return im.crop((0, 0, w, h)) if im.size != (w, h) else im


def save(im, rel):
    p = os.path.join(OUT, rel); os.makedirs(os.path.dirname(p), exist_ok=True)
    im.save(p, optimize=True)


def main():
    B = 1024
    sq = raster(oneui.full_icon(KEY, 'xs'), 'icon_squircle', B, B)
    rd = raster(oneui.full_icon(KEY, 'xr', shape='circle'), 'icon_round', B, B)
    ps = raster(oneui.full_icon(KEY, 'xp', shape='square'), 'icon_play', B, B)
    fg = raster(oneui.adaptive_fg(KEY), 'fg', B, B)
    bg = raster(oneui.adaptive_bg(KEY), 'bg', B, B)
    mo = raster(oneui.adaptive_mono(KEY), 'mono', B, B)
    for d, k in DENS:
        n = int(round(48 * k)); a = int(round(108 * k))
        save(sq.resize((n, n), Image.LANCZOS), 'mipmap-%s/ic_launcher.png' % d)
        save(rd.resize((n, n), Image.LANCZOS), 'mipmap-%s/ic_launcher_round.png' % d)
        save(fg.resize((a, a), Image.LANCZOS), 'mipmap-%s/ic_launcher_foreground.png' % d)
        save(bg.resize((a, a), Image.LANCZOS).convert('RGB'), 'mipmap-%s/ic_launcher_background.png' % d)
        save(mo.resize((a, a), Image.LANCZOS), 'mipmap-%s/ic_launcher_monochrome.png' % d)
    save(ps.resize((512, 512), Image.LANCZOS).convert('RGB'), 'play_store_icon.png')
    tv = raster(oneui.banner(KEY), 'tv', 1280, 720, transparent=False)
    save(tv.resize((640, 360), Image.LANCZOS).convert('RGB'), 'drawable-xhdpi/tv_banner.png')
    save(tv.resize((320, 180), Image.LANCZOS).convert('RGB'), 'drawable-mdpi/tv_banner.png')
    for dark, suffix in [(True, ''), (False, '_light')]:
        s, w, h = oneui.header(KEY, dark)
        W = 2400; H = int(round(h * W / w))
        im = raster(s, 'header%s' % suffix, W, H)
        im = im.crop(im.getchannel('A').getbbox())
        for tw, nm in [(1200, 'logo_header%s.png' % suffix), (600, 'logo_header%s_small.png' % suffix)]:
            save(im.resize((tw, int(round(im.size[1] * tw / im.size[0]))), Image.LANCZOS), 'drawable-nodpi/' + nm)
    # QA sheet
    sh = Image.new('RGB', (1400, 760), (14, 14, 16))
    ImageDraw.Draw(sh).rectangle((0, 380, 1400, 760), fill=(240, 238, 244))
    x = 20
    for d, k in DENS:
        for row, nm in [(20, 'ic_launcher'), (230, 'ic_launcher_round')]:
            im = Image.open(os.path.join(OUT, 'mipmap-%s/%s.png' % (d, nm))); sh.paste(im, (x, row), im)
        im = Image.open(os.path.join(OUT, 'mipmap-%s/ic_launcher.png' % d)); sh.paste(im, (x, 400), im)
        x += int(48 * k) + 20
    comp = Image.alpha_composite(Image.open(os.path.join(OUT, 'mipmap-xxxhdpi/ic_launcher_background.png')).convert('RGBA'),
                                 Image.open(os.path.join(OUT, 'mipmap-xxxhdpi/ic_launcher_foreground.png')))
    c = comp.crop((72, 72, 360, 360))
    m = Image.new('L', (1152, 1152), 0); ImageDraw.Draw(m).ellipse((0, 0, 1151, 1151), fill=255); m = m.resize((288, 288), Image.LANCZOS)
    cc = c.copy(); cc.putalpha(m); sh.paste(cc, (760, 20), cc)
    fgv = Image.alpha_composite(Image.new('RGBA', (432, 432), (60, 60, 70, 255)),
                                Image.open(os.path.join(OUT, 'mipmap-xxxhdpi/ic_launcher_foreground.png')))
    dr = ImageDraw.Draw(fgv); r = 432 * 33 / 108
    dr.ellipse((216 - r, 216 - r, 216 + r, 216 + r), outline=(255, 80, 80, 255), width=2)
    sh.paste(fgv.resize((288, 288)), (1080, 20))
    mono = Image.open(os.path.join(OUT, 'mipmap-xxxhdpi/ic_launcher_monochrome.png')).crop((72, 72, 360, 360)).resize((144, 144))
    mb = Image.alpha_composite(Image.new('RGBA', (144, 144), (59, 44, 134, 255)), mono); sh.paste(mb, (600, 230))
    hd = Image.open(os.path.join(OUT, 'drawable-nodpi/logo_header_small.png')); sh.paste(hd, (20, 560), hd)
    hl = Image.open(os.path.join(OUT, 'drawable-nodpi/logo_header_light_small.png')); sh.paste(hl, (20, 660), hl)
    tvs = Image.open(os.path.join(OUT, 'drawable-mdpi/tv_banner.png')); sh.paste(tvs, (700, 400))
    sh.save(os.path.join(SRC, 'qa_sheet.png'))


if __name__ == '__main__':
    main()
