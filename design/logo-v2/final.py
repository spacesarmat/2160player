# -*- coding: utf-8 -*-
"""Final Android assets: 21/60 gold monogram icon set + concept-1 banner/header PNGs."""
import math, os, sys, subprocess, shutil
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from engine import *
from glyphs import numerals, layout
import gen
from gen import S1, GOLD_FRONT, front_grad, svg_open, beam, c1_scene, STONE
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'final')
TMP = os.path.join(OUT, '_src')
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
os.makedirs(TMP, exist_ok=True)

LEAD = 122          # line spacing of the 21 / 60 stack (glyph units, cap height 100)
EYE = (-170, 18, -470)
LIGHT = (-0.55, 0.55, -0.62)


def mono_polys():
    G = numerals(S1)
    top, w1 = layout('21', G, 13)
    bot, w2 = layout('60', G, 13)
    polys = translate(top, -w1 / 2, 0) + translate(bot, -w2 / 2, -LEAD)
    return translate(polys, 0, LEAD / 2 - 50)   # vertical centre at y=0


def fit_circle(sc, cx, cy, R):
    pts = sc.subject
    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
    bx, by = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    d = max(math.hypot(x - bx, y - by) for x, y in pts)
    k = R / d
    sc.T = (k, cx - bx * k, cy - by * k)


def mono_scene():
    sc = Scene(Camera(EYE, (0, 0, 0)), light=LIGHT)
    top = 100 + LEAD / 2 - 50; bot = -LEAD / 2 - 50
    sc.extrude(mono_polys(), z0=0, depth=50, bevel=4.2, mat=GOLD,
               front=front_grad(sc, top, bot, GOLD_FRONT), side_fade=0.30)
    return sc


def gold_bar(prefix, defs, x0, x1, y):
    defs.append('<linearGradient id="%s-bar" gradientUnits="userSpaceOnUse" x1="%.1f" y1="0" x2="%.1f" y2="0">'
                '<stop offset="0" stop-color="#6B3F04" stop-opacity="0"/><stop offset="0.5" stop-color="#FFD36B"/>'
                '<stop offset="1" stop-color="#6B3F04" stop-opacity="0"/></linearGradient>' % (prefix, x0, x1))
    return '<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" rx="%.1f" fill="url(#%s-bar)"/>' % (
        x0, y, x1 - x0, (x1 - x0) * 0.022, (x1 - x0) * 0.011, prefix)


def background(prefix, defs, rounded=False, beams=True):
    defs.append('<radialGradient id="%s-bg" gradientUnits="userSpaceOnUse" cx="256" cy="300" r="360">'
                '<stop offset="0" stop-color="#2E2109"/><stop offset="0.5" stop-color="#151310"/>'
                '<stop offset="1" stop-color="#0B0B0D"/></radialGradient>' % prefix)
    s = '<rect width="512" height="512"%s fill="url(#%s-bg)"/>' % (' rx="112"' if rounded else '', prefix)
    if beams:
        ids = Ids(prefix + 'b')
        s += ''.join(beam(defs, ids, 256, 330, a, 360, 10, 130, '#FFE3A6', op)
                     for a, op in [(-56, .10), (-28, .15), (0, .13), (28, .15), (56, .10)])
    return s


def mono_body(prefix, defs, R, cx=256, cy=250, bar=True, barw=0.62):
    sc = mono_scene()
    fit_circle(sc, cx, cy, R)
    ids = Ids(prefix)
    body = sc.render(ids, defs)
    if bar:
        xs = [sc.tp(p)[0] for p in sc.subject]; ys = [sc.tp(p)[1] for p in sc.subject]
        w = max(xs) - min(xs)
        body += gold_bar(prefix, defs, cx - w * barw, cx + w * barw, max(ys) + R * 0.06)
    return body


def icon_final():
    defs = []; p = 'if'
    bg = background(p, defs, rounded=True)
    body = mono_body(p + 'm', defs, 205, cy=246)
    return svg_open(512, 512) + '<defs>%s</defs>' % ''.join(defs) + bg + body + '</svg>'


def play_icon():
    defs = []; p = 'ps'
    bg = background(p, defs, rounded=False)
    body = mono_body(p + 'm', defs, 205, cy=246)
    return svg_open(512, 512) + '<defs>%s</defs>' % ''.join(defs) + bg + body + '</svg>'


SAFE_R = 512 * 33 / 108          # 156.4 px: radius of the 66dp safe circle on a 108dp canvas


def icon_foreground():
    defs = []; p = 'fg'
    body = mono_body(p, defs, SAFE_R - 16, cy=250, barw=0.45)
    return svg_open(512, 512) + '<defs>%s</defs>' % ''.join(defs) + body + '</svg>'


def icon_background():
    defs = []; p = 'bg'
    bg = background(p, defs, rounded=False)
    return svg_open(512, 512) + '<defs>%s</defs>' % ''.join(defs) + bg + '</svg>'


def icon_monochrome():
    polys = mono_polys()
    xs = [x for q in polys for x, _ in q.exterior.coords]; ys = [y for q in polys for _, y in q.exterior.coords]
    bx, by = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    d = max(math.hypot(x - bx, y - by) for q in polys for x, y in q.exterior.coords)
    k = (SAFE_R - 10) / d
    dpath = poly2d(polys, lambda pt: (256 + (pt[0] - bx) * k, 256 - (pt[1] - by) * k))
    return svg_open(512, 512) + '<path d="%s" fill="#FFFFFF" fill-rule="evenodd"/></svg>' % dpath


# ---------------------------------------------------------------- header (transparent lockup)
def header_svg():
    S1['gap'] = 22                     # more air between digits: fixes the 1/6 overlap
    sc, width = c1_scene(cam_eye=(-150, 12, -560), target=(0, 50, 0))
    S1['gap'] = 15
    ids = Ids('hd'); defs = []
    sc.fit((20, 20, 2360, 1200))
    body = sc.render(ids, defs)
    xs = [sc.tp(p)[0] for p in sc.subject]; ys = [sc.tp(p)[1] for p in sc.subject]
    W, H = int(max(xs) + 20), int(max(ys) + 20)
    return svg_open(W, H) + '<defs>%s</defs>' % ''.join(defs) + body + '</svg>', W, H


# ---------------------------------------------------------------- rasterising
def chrome_png(svg_path, w, h, out_png, transparent=True):
    html = os.path.join(TMP, '_r.html')
    with open(html, 'w', encoding='utf-8') as f:
        f.write('<!doctype html><html><body style="margin:0;background:transparent;overflow:hidden">'
                '<img src="%s" style="display:block;width:%dpx;height:%dpx"></body></html>'
                % ('file:///' + svg_path.replace('\\', '/'), w, h))
    args = [CHROME, '--headless=new', '--disable-gpu', '--hide-scrollbars', '--allow-file-access-from-files',
            '--screenshot=' + out_png, '--window-size=%d,%d' % (w, h)]
    if transparent: args.append('--default-background-color=00000000')
    args.append('file:///' + html.replace('\\', '/'))
    subprocess.run(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
    im = Image.open(out_png).convert('RGBA')
    if im.size != (w, h): im = im.crop((0, 0, w, h))
    return im


def circle(im):
    big = im.size[0] * 4
    m = Image.new('L', (big, big), 0)
    ImageDraw.Draw(m).ellipse((0, 0, big - 1, big - 1), fill=255)
    m = m.resize(im.size, Image.LANCZOS)
    out = im.copy()
    a = Image.composite(im.getchannel('A'), Image.new('L', im.size, 0), m)
    out.putalpha(a)
    return out


DENS = [('mdpi', 1.0), ('hdpi', 1.5), ('xhdpi', 2.0), ('xxhdpi', 3.0), ('xxxhdpi', 4.0)]


def save(im, rel):
    path = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path, optimize=True)
    return path


def main():
    svgs = {'icon-final.svg': icon_final(), 'icon-foreground.svg': icon_foreground(),
            'icon-background.svg': icon_background(), 'icon-monochrome.svg': icon_monochrome(),
            'play-store-icon.svg': play_icon()}
    for n, s in svgs.items():
        with open(os.path.join(OUT, n), 'w', encoding='utf-8') as f: f.write(s)
    hs, hw, hh = header_svg()
    with open(os.path.join(OUT, 'logo-header.svg'), 'w', encoding='utf-8') as f: f.write(hs)
    with open(os.path.join(OUT, 'tv-banner.svg'), 'w', encoding='utf-8') as f: S1['gap'] = 22; dflt = gen.c1_scene.__defaults__; gen.c1_scene.__defaults__ = ((-150, 12, -560),) + dflt[1:]; f.write(gen.c1_banner('tvb')); gen.c1_scene.__defaults__ = dflt; S1['gap'] = 15

    S = 1024
    big = {}
    for n in ['icon-final', 'icon-foreground', 'icon-background', 'icon-monochrome', 'play-store-icon']:
        big[n] = chrome_png(os.path.join(OUT, n + '.svg'), S, S, os.path.join(TMP, n + '.png'))
    # round legacy icon: full-bleed background + monogram, circle crop
    with open(os.path.join(TMP, 'round.svg'), 'w', encoding='utf-8') as f: f.write(play_icon())
    round_big = circle(chrome_png(os.path.join(TMP, 'round.svg'), S, S, os.path.join(TMP, 'round.png')))

    for d, k in DENS:
        n = int(round(48 * k)); a = int(round(108 * k))
        save(big['icon-final'].resize((n, n), Image.LANCZOS), 'mipmap-%s/ic_launcher.png' % d)
        save(round_big.resize((n, n), Image.LANCZOS), 'mipmap-%s/ic_launcher_round.png' % d)
        save(big['icon-foreground'].resize((a, a), Image.LANCZOS), 'mipmap-%s/ic_launcher_foreground.png' % d)
        save(big['icon-background'].resize((a, a), Image.LANCZOS).convert('RGB'), 'mipmap-%s/ic_launcher_background.png' % d)
        save(big['icon-monochrome'].resize((a, a), Image.LANCZOS), 'mipmap-%s/ic_launcher_monochrome.png' % d)
    save(big['play-store-icon'].resize((512, 512), Image.LANCZOS).convert('RGB'), 'play_store_icon.png')

    tv = chrome_png(os.path.join(OUT, 'tv-banner.svg'), 1280, 720, os.path.join(TMP, 'tv.png'), transparent=False)
    save(tv.resize((640, 360), Image.LANCZOS).convert('RGB'), 'drawable-xhdpi/tv_banner.png')
    save(tv.resize((320, 180), Image.LANCZOS).convert('RGB'), 'drawable-mdpi/tv_banner.png')

    hd = chrome_png(os.path.join(OUT, 'logo-header.svg'), hw, hh, os.path.join(TMP, 'hd.png'))
    hd = hd.crop(hd.getchannel('A').getbbox())
    for w, name in [(1200, 'logo_header.png'), (600, 'logo_header_small.png')]:
        h = int(round(hd.size[1] * w / hd.size[0]))
        save(hd.resize((w, h), Image.LANCZOS), 'drawable-nodpi/' + name)

    # ------- QA sheet
    sheet = Image.new('RGB', (1500, 1100), (236, 236, 239))
    dark = Image.new('RGB', (1500, 560), (14, 14, 16)); sheet.paste(dark, (0, 540))
    x = 20
    for d, k in DENS:
        im = Image.open(os.path.join(OUT, 'mipmap-%s/ic_launcher.png' % d)); sheet.paste(im, (x, 20), im)
        im = Image.open(os.path.join(OUT, 'mipmap-%s/ic_launcher_round.png' % d)); sheet.paste(im, (x, 230), im)
        x += int(48 * k) + 20
    fg = Image.open(os.path.join(OUT, 'mipmap-xxxhdpi/ic_launcher_foreground.png'))
    bgp = Image.open(os.path.join(OUT, 'mipmap-xxxhdpi/ic_launcher_background.png')).convert('RGBA')
    comp = Image.alpha_composite(bgp, fg)
    # adaptive masks: visible 72dp of 108dp -> crop centre 2/3
    c = comp.crop((72, 72, 360, 360))
    sheet.paste(circle(c), (760, 20), circle(c))
    sq = Image.new('L', (288 * 4, 288 * 4), 0)
    ImageDraw.Draw(sq).rounded_rectangle((0, 0, 288 * 4 - 1, 288 * 4 - 1), radius=288 * 4 * 0.32, fill=255)
    sq = sq.resize((288, 288), Image.LANCZOS)
    cc = c.copy(); cc.putalpha(sq); sheet.paste(cc, (1070, 20), cc)
    # safe-zone overlay on raw foreground
    fgv = Image.alpha_composite(Image.new('RGBA', fg.size, (60, 60, 70, 255)), fg)
    dr = ImageDraw.Draw(fgv); r = 432 * 33 / 108
    dr.ellipse((216 - r, 216 - r, 216 + r, 216 + r), outline=(255, 80, 80, 255), width=2)
    dr.rectangle((72, 72, 360, 360), outline=(80, 200, 255, 255), width=1)
    sheet.paste(fgv, (20, 560))
    mono = Image.open(os.path.join(OUT, 'mipmap-xxxhdpi/ic_launcher_monochrome.png'))
    mb = Image.new('RGBA', (288, 288), (40, 60, 90, 255)); mc = mono.crop((72, 72, 360, 360))
    mb = Image.alpha_composite(mb, mc); sheet.paste(circle(mb), (480, 600), circle(mb))
    hs_ = Image.open(os.path.join(OUT, 'drawable-nodpi/logo_header_small.png')); sheet.paste(hs_, (800, 600), hs_)
    tvs = Image.open(os.path.join(OUT, 'drawable-mdpi/tv_banner.png')); sheet.paste(tvs, (800, 600 + hs_.size[1] + 30))
    sheet.save(os.path.join(TMP, 'qa_sheet.png'))


if __name__ == '__main__':
    main()
