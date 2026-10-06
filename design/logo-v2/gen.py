# -*- coding: utf-8 -*-
"""2160 Player — logo v2 (cinematic monument wordmarks). Writes SVGs + preview.html."""
import math, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from engine import *
from glyphs import numerals, layout, letters, flute

OUT = os.path.dirname(os.path.abspath(__file__))
BG = '#0E0E10'; AMBER = '#FFB020'; LIGHT = '#F2F2F2'

STONE = ramp([(0, '#060607'), (0.3, '#121216'), (0.55, '#24242A'), (0.8, '#3A3A42'), (1, '#5A5A64')])

GOLD_FRONT = [(0, '#FFF3CF'), (0.16, '#FFD46A'), (0.40, '#F0A21A'), (0.52, '#B86E06'),
              (0.56, '#FFC94D'), (0.74, '#E0900F'), (1, '#7A4504')]
CHROME_FRONT = [(0, '#FFFFFF'), (0.22, '#E9E9EE'), (0.46, '#A9A9B3'), (0.5, '#55555F'),
                (0.54, '#D9D9E0'), (0.78, '#B4B0AA'), (0.92, '#C79A55'), (1, '#8A5A12')]


def svg_open(w, h, extra=''):
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d"%s>' % (w, h, w, h, extra)


def front_grad(sc, y_top, y_bot, stops, x=0):
    return ('lin', sc.cam.proj((x, y_top, 0)), sc.cam.proj((x, y_bot, 0)), stops)


def beam(defs, ids, ox, oy, ang, length, w0, w1, color, op, blur=None):
    """searchlight beam polygon in final coords; ang = degrees from vertical (+ = right)"""
    a = math.radians(ang)
    dx, dy = math.sin(a), -math.cos(a)
    px, py = -dy, dx
    ex, ey = ox + dx * length, oy + dy * length
    pts = [(ox + px * w0 / 2, oy + py * w0 / 2), (ex + px * w1 / 2, ey + py * w1 / 2),
           (ex - px * w1 / 2, ey - py * w1 / 2), (ox - px * w0 / 2, oy - py * w0 / 2)]
    gid = ids('b')
    defs.append('<linearGradient id="%s" gradientUnits="userSpaceOnUse" x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f">'
                '<stop offset="0" stop-color="%s" stop-opacity="%.3f"/><stop offset="0.35" stop-color="%s" stop-opacity="%.3f"/>'
                '<stop offset="1" stop-color="%s" stop-opacity="0"/></linearGradient>'
                % (gid, ox, oy, ex, ey, color, op, color, op * 0.45, color))
    f = ' filter="url(#%s)"' % blur if blur else ''
    return '<path d="%s" fill="url(#%s)"%s/>' % (pathd([pts]), gid, f)


def text_flat(word, H, x_center, y_base, fill, gap=26, W=62, s=19, c=12, extra=''):
    """flat 2D letters (y down svg) -> path"""
    L = letters(W, s, c)
    polys, width = layout(word, L, gap)
    k = H / 100.0
    x0 = x_center - width * k / 2
    d = poly2d(polys, lambda p: (x0 + p[0] * k, y_base - p[1] * k))
    return '<path d="%s" fill="%s" fill-rule="evenodd"%s/>' % (d, fill, extra), width * k


# =====================================================================================
# CONCEPT 1 — "Монумент": gold block numerals on a two-step obsidian pedestal,
#              PLAYER cast on the plinth, a crown of searchlights behind.
# =====================================================================================
S1 = dict(W=70, s=24, c=16, ci=5, gap=15, W1=52, yri=0.56, term=10, yB=60, wt=0.86, base1=True)


def c1_scene(cam_eye=(-240, 12, -540), target=(0, 50, 0), pedestal=True, player=True):
    G = numerals(S1)
    polys, width = layout('2160', G, S1['gap'])
    polys = translate(polys, -width / 2, 0)
    cam = Camera(cam_eye, target)
    sc = Scene(cam, light=(-0.55, 0.55, -0.62))
    if pedestal:
        hw = width / 2
        sc.box3(-hw - 22, hw + 22, -44, 0, -16, 88, STONE, layer=2,
                front=('lin', cam.proj((0, 0, -16)), cam.proj((0, -44, -16)),
                       [(0, '#3A3A44'), (0.06, '#22222A'), (0.5, '#141418'), (1, '#08080A')]),
                top=('lin', cam.proj((0, 0, -16)), cam.proj((0, 0, 88)), [(0, '#6A5530'), (0.25, '#2A2620'), (1, '#0E0E10')]))
        sc.box3(-hw - 46, hw + 46, -74, -44, -34, 110, STONE, layer=1,
                front=('lin', cam.proj((0, -44, -34)), cam.proj((0, -74, -34)),
                       [(0, '#2A2A30'), (0.1, '#151519'), (1, '#070708')]),
                top=('lin', cam.proj((0, -44, -34)), cam.proj((0, -44, 110)), [(0, '#4A3E26'), (0.3, '#1E1C18'), (1, '#0C0C0E')]))
        # gold trim line on the top step edge
        sc.box3(-hw - 22, hw + 22, -3.2, 0, -16.6, -16, GOLD, layer=3,
                front=('lin', cam.proj((-hw, 0, -16.6)), cam.proj((hw, 0, -16.6)),
                       [(0, '#6B3F04'), (0.3, '#FFB020'), (0.5, '#FFE7A6'), (0.7, '#FFB020'), (1, '#6B3F04')]))
        sc.box3(-hw - 46, hw + 46, -46.4, -44, -34.6, -34, GOLD, layer=3,
                front=('lin', cam.proj((-hw - 46, 0, -34.6)), cam.proj((hw + 46, 0, -34.6)),
                       [(0, '#3B2303'), (0.35, '#C98410'), (0.5, '#FFD978'), (0.65, '#C98410'), (1, '#3B2303')]))
    sc.extrude(polys, z0=0, depth=72, bevel=3.6, mat=GOLD,
               front=front_grad(sc, 100, 0, GOLD_FRONT), side_fade=0.30)
    if player and pedestal:
        L = letters(62, 20, 12)
        lp, lw = layout('PLAYER', L, 34)
        k = 0.30
        lp = translate(lp, -lw * k / 2, -37, k)
        sc.extrude(lp, z0=-18.6, depth=2.6, bevel=0.9, mat=GOLD, layer_side=30, layer_front=40,
                   front=('lin', cam.proj((0, -7, -18.6)), cam.proj((0, -37, -18.6)),
                          [(0, '#FFF0C0'), (0.45, '#FFC040'), (0.55, '#C47A08'), (1, '#FFB020')]))
    return sc, width


def c1_hero(prefix='c1h'):
    W, H = 1600, 900
    ids = Ids(prefix); defs = []
    sc, width = c1_scene()
    sc.fit((300, 170, 1000, 640), align='bottom')
    body = sc.render(ids, defs)
    # background
    bg = []
    defs.append('<linearGradient id="%s-sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#050506"/>'
                '<stop offset="0.55" stop-color="#0E0E10"/><stop offset="0.82" stop-color="#1A140A"/><stop offset="1" stop-color="#0A0907"/></linearGradient>' % prefix)
    defs.append('<radialGradient id="%s-halo" cx="0.5" cy="0.62" r="0.5"><stop offset="0" stop-color="#FFB020" stop-opacity="0.32"/>'
                '<stop offset="0.45" stop-color="#FF8A00" stop-opacity="0.08"/><stop offset="1" stop-color="#FF8A00" stop-opacity="0"/></radialGradient>' % prefix)
    defs.append('<filter id="%s-blur" x="-50%%" y="-50%%" width="200%%" height="200%%"><feGaussianBlur stdDeviation="7"/></filter>' % prefix)
    defs.append('<filter id="%s-blur2" x="-50%%" y="-50%%" width="200%%" height="200%%"><feGaussianBlur stdDeviation="22"/></filter>' % prefix)
    bg.append('<rect width="%d" height="%d" fill="url(#%s-sky)"/>' % (W, H, prefix))
    # crown of searchlights from one focus behind the monument
    ox, oy = sc.P((0, 30, 140))
    beams = []
    for ang, ln, op in [(-62, 900, .22), (-40, 1000, .30), (-20, 1050, .36), (0, 1000, .30), (20, 1050, .36), (40, 1000, .30), (62, 900, .22)]:
        beams.append(beam(defs, ids, ox, oy, ang, ln, 14, 190, '#FFE3A6', op, blur='%s-blur' % prefix))
        beams.append(beam(defs, ids, ox, oy, ang, ln * 0.8, 4, 46, '#FFF6E0', op * 0.9, blur='%s-blur' % prefix))
    bg.append('<g style="mix-blend-mode:screen">%s</g>' % ''.join(beams))
    bg.append('<ellipse cx="%.1f" cy="%.1f" rx="760" ry="330" fill="url(#%s-halo)"/>' % (ox, oy, prefix))
    # floor horizon
    fx, fy = sc.P((0, -74, -34))
    defs.append('<linearGradient id="%s-floor" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#1B1508"/><stop offset="1" stop-color="#050505"/></linearGradient>' % prefix)
    hy = sc.P((0, -74, 84))[1]
    bg.append('<rect x="0" y="%.1f" width="%d" height="%.1f" fill="url(#%s-floor)"/>' % (hy, W, H - hy, prefix))
    bg.append('<ellipse cx="%.1f" cy="%.1f" rx="520" ry="26" fill="#FFB020" opacity="0.22" filter="url(#%s-blur2)"/>' % (fx, hy + 4, prefix))
    # foreground vignette
    defs.append('<radialGradient id="%s-vig" cx="0.5" cy="0.5" r="0.75"><stop offset="0.6" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity="0.7"/></radialGradient>' % prefix)
    fg = '<rect width="%d" height="%d" fill="url(#%s-vig)"/>' % (W, H, prefix)
    return svg_open(W, H) + '<defs>%s</defs>' % ''.join(defs) + ''.join(bg) + body + fg + '</svg>'


def icon_bg(prefix, defs, glow_c='#FFB020', glow_y=0.62):
    defs.append('<radialGradient id="%s-ibg" gradientUnits="userSpaceOnUse" cx="256" cy="%d" r="330">'
                '<stop offset="0" stop-color="#2A1E08"/><stop offset="0.55" stop-color="#141210"/><stop offset="1" stop-color="#0B0B0D"/></radialGradient>' % (prefix, 512 * glow_y))
    return '<rect width="512" height="512" rx="112" fill="url(#%s-ibg)"/>' % prefix


def c1_icon(prefix='c1i'):
    ids = Ids(prefix); defs = []
    sc, width = c1_scene(cam_eye=(-60, 18, -430), target=(0, 46, 0), pedestal=False)
    sc.fit((52, 150, 408, 220))
    body = sc.render(ids, defs)
    bg = icon_bg(prefix, defs)
    ox, oy = sc.P((0, 30, 140))
    beams = ''.join(beam(defs, ids, ox, oy, a, 330, 8, 120, '#FFE3A6', op) for a, op in
                    [(-48, .16), (-24, .22), (0, .2), (24, .22), (48, .16)])
    # plinth bar under numerals (flat, vector-friendly)
    x0, y0 = sc.P((-width / 2 - 14, 0, -6)); x1, _ = sc.P((width / 2 + 14, 0, -6))
    defs.append('<linearGradient id="%s-bar" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#6B3F04" stop-opacity="0"/>'
                '<stop offset="0.5" stop-color="#FFD36B"/><stop offset="1" stop-color="#6B3F04" stop-opacity="0"/></linearGradient>' % prefix)
    bar = '<rect x="%.1f" y="%.1f" width="%.1f" height="7" rx="3.5" fill="url(#%s-bar)"/>' % (x0, y0 + 12, x1 - x0, prefix)
    return svg_open(512, 512) + '<defs>%s</defs>' % ''.join(defs) + bg + beams + body + bar + '</svg>'


def c1_banner(prefix='c1b'):
    ids = Ids(prefix); defs = []
    sc, width = c1_scene()
    sc.fit((36, 16, 248, 154), align='bottom')
    body = sc.render(ids, defs)
    defs.append('<linearGradient id="%s-sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#060607"/><stop offset="0.7" stop-color="#0E0E10"/><stop offset="1" stop-color="#1A140A"/></linearGradient>' % prefix)
    ox, oy = sc.P((0, 30, 140))
    beams = ''.join(beam(defs, ids, ox, oy, a, 200, 4, 50, '#FFE3A6', op) for a, op in
                    [(-60, .16), (-36, .22), (-12, .26), (12, .26), (36, .22), (60, .16)])
    return (svg_open(320, 180) + '<defs>%s</defs>' % ''.join(defs) +
            '<rect width="320" height="180" fill="url(#%s-sky)"/>' % prefix + beams + body + '</svg>')


# =====================================================================================
CONCEPTS = []


def write(name, s):
    with open(os.path.join(OUT, name), 'w', encoding='utf-8') as f:
        f.write(s)
    return s


def build():
    res = {}
    res['c1'] = dict(hero=write('concept-1-monument-hero.svg', c1_hero()),
                     icon=write('concept-1-monument-icon.svg', c1_icon()),
                     banner=write('concept-1-monument-tv-banner.svg', c1_banner()))
    try:
        import concepts23
        res.update(concepts23.build(write))
    except ImportError:
        pass
    import preview
    preview.make(res, OUT)


if __name__ == '__main__':
    build()

