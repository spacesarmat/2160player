# -*- coding: utf-8 -*-
"""Concepts 2 and 3."""
import math
from shapely import affinity
from engine import *
from glyphs import numerals, layout, letters
from gen import svg_open, front_grad, beam, text_flat, icon_bg, GOLD_FRONT, CHROME_FRONT, STONE

# =====================================================================================
# CONCEPT 2 — "Премьера 4K": chrome numerals with molten-amber depth, hard 3/4 view,
#              perspective pixel grid on faces, two top spotlights, mirror floor.
# =====================================================================================
S2 = dict(W=74, s=26, c=24, ci=8, gap=16, W1=50, yri=0.55, term=12, yB=66, wt=0.9, base1=False)
C2_LIGHT = (0.35, 0.75, -0.55)


def c2_polys(text='2160'):
    G = numerals(S2)
    polys, width = layout(text, G, S2['gap'])
    return translate(polys, -width / 2, 0), width


def c2_extrude(sc, polys, grid=True, mirror=False):
    if mirror:
        polys = [q for p in polys for q in clean(affinity.scale(p, 1, -1, origin=(0, 0)))]
        fg = front_grad(sc, -100, 0, CHROME_FRONT)
    else:
        fg = front_grad(sc, 100, 0, CHROME_FRONT)
    sc.extrude(polys, z0=0, depth=46, bevel=3.2, mat=AMBERR, front=fg, side_fade=0.22, side_boost=1.15,
               grid=(3.2 if grid else None), grid_color='#2A2A32', grid_opacity=0.22)


def c2_scene(eye=(235, 30, -500), target=(14, 48, 0), grid=True):
    cam = Camera(eye, target)
    sc = Scene(cam, light=C2_LIGHT)
    polys, width = c2_polys()
    c2_extrude(sc, polys, grid=grid)
    return sc, polys, width


def c2_hero(prefix='c2h'):
    W, H = 1600, 900
    ids = Ids(prefix); defs = []
    sc, polys, width = c2_scene()
    sc.fit((250, 150, 1100, 470), align='bottom')
    body = sc.render(ids, defs)
    # reflection: same camera, mirrored geometry
    rf = Scene(sc.cam, light=C2_LIGHT)
    c2_extrude(rf, polys, grid=False, mirror=True)
    rf.T = sc.T
    rbody = rf.render(ids, defs)
    fy = sc.P((0, 0, 0))[1]
    defs.append('<linearGradient id="%s-sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#08080A"/>'
                '<stop offset="0.6" stop-color="#121014"/><stop offset="1" stop-color="#0E0E10"/></linearGradient>' % prefix)
    defs.append('<linearGradient id="%s-rm" gradientUnits="userSpaceOnUse" x1="0" y1="%.1f" x2="0" y2="%.1f">'
                '<stop offset="0" stop-color="#fff" stop-opacity="0.38"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></linearGradient>' % (prefix, fy, fy + 210))
    defs.append('<mask id="%s-mask"><rect width="%d" height="%d" fill="url(#%s-rm)"/></mask>' % (prefix, W, H, prefix))
    defs.append('<filter id="%s-blur" x="-50%%" y="-50%%" width="200%%" height="200%%"><feGaussianBlur stdDeviation="10"/></filter>' % prefix)
    defs.append('<filter id="%s-rb"><feGaussianBlur stdDeviation="1.6"/></filter>' % prefix)
    defs.append('<radialGradient id="%s-pool" cx="0.5" cy="0.5" r="0.5"><stop offset="0" stop-color="#FFB020" stop-opacity="0.45"/>'
                '<stop offset="1" stop-color="#FFB020" stop-opacity="0"/></radialGradient>' % prefix)
    out = [svg_open(W, H), '', '<rect width="%d" height="%d" fill="url(#%s-sky)"/>' % (W, H, prefix)]
    # floor
    defs.append('<linearGradient id="%s-floor" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#1A1610"/><stop offset="1" stop-color="#060607"/></linearGradient>' % prefix)
    out.append('<rect x="0" y="%.1f" width="%d" height="%.1f" fill="url(#%s-floor)"/>' % (fy, W, H - fy, prefix))
    cx = sc.P((0, 0, 30))[0]
    out.append('<ellipse cx="%.1f" cy="%.1f" rx="640" ry="60" fill="url(#%s-pool)"/>' % (cx, fy + 8, prefix))
    # two spotlights from the top corners, crossing on the monument
    tx, ty = sc.P((0, 60, 20))
    for sx, sy in [(160, -40), (1440, -40)]:
        ang = math.degrees(math.atan2(tx - sx, -(ty - sy)))
        ang = 180 - ang if False else ang
        L = math.hypot(tx - sx, ty - sy) * 1.25
        # beam that points from the lamp towards the monument: build with origin at lamp, angle measured from 'up'
        a = math.atan2(ty - sy, tx - sx)
        deg = math.degrees(a) + 90  # convert to 'from vertical'
        out.append(beam(defs, ids, sx, sy, deg, L, 24, 420, '#FFF1D0', 0.26, blur='%s-blur' % prefix))
        out.append(beam(defs, ids, sx, sy, deg, L * 0.9, 8, 140, '#FFF8E8', 0.16, blur='%s-blur' % prefix))
        out.append('<circle cx="%d" cy="%d" r="60" fill="#FFE7B0" opacity="0.5" filter="url(#%s-blur)"/>' % (sx, sy + 20, prefix))
    out.append('<g mask="url(#%s-mask)" filter="url(#%s-rb)">%s</g>' % (prefix, prefix, rbody))
    out.append(body)
    # floor edge highlight
    out.append('<rect x="0" y="%.1f" width="%d" height="1.2" fill="#FFB020" opacity="0.25"/>' % (fy, W))
    # title card PLAYER
    t, tw = text_flat('PLAYER', 54, W / 2, 812, 'url(#%s-pt)' % prefix, gap=58)
    defs.append('<linearGradient id="%s-pt" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF"/><stop offset="0.5" stop-color="#E6E6EA"/>'
                '<stop offset="0.52" stop-color="#9A9AA2"/><stop offset="1" stop-color="#F2F2F2"/></linearGradient>' % prefix)
    out.append(t)
    lw = 150
    out.append('<rect x="%.1f" y="784" width="%d" height="2" fill="#FFB020"/>' % (W / 2 - tw / 2 - 40 - lw, lw))
    out.append('<rect x="%.1f" y="784" width="%d" height="2" fill="#FFB020"/>' % (W / 2 + tw / 2 + 40, lw))
    defs.append('<radialGradient id="%s-vig" cx="0.5" cy="0.5" r="0.75"><stop offset="0.55" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity="0.75"/></radialGradient>' % prefix)
    out.append('<rect width="%d" height="%d" fill="url(#%s-vig)"/>' % (W, H, prefix))
    out[1] = '<defs>%s</defs>' % ''.join(defs)
    return ''.join(out) + '</svg>'


def c2_icon(prefix='c2i'):
    ids = Ids(prefix); defs = []
    cam = Camera((190, 30, -440), (10, 0, 0))
    sc = Scene(cam, light=C2_LIGHT)
    G = numerals(S2)
    top, w1 = layout('21', G, S2['gap'])
    bot, w2 = layout('60', G, S2['gap'])
    lead = 118
    polys = translate(top, -w1 / 2, 0) + translate(bot, -w2 / 2, -lead)
    polys = translate(polys, 0, lead / 2 - 50)
    sc.extrude(polys, z0=0, depth=56, bevel=4.0, mat=AMBERR,
               front=front_grad(sc, 100 + lead / 2 - 50, -lead / 2 - 50, CHROME_FRONT), side_fade=0.22, side_boost=1.15)
    sc.fit((96, 70, 320, 372))
    body = sc.render(ids, defs)
    bg = icon_bg(prefix, defs, glow_y=0.55)
    defs.append('<linearGradient id="%s-sp" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFF1D0" stop-opacity="0.16"/>'
                '<stop offset="1" stop-color="#FFF1D0" stop-opacity="0"/></linearGradient>' % prefix)
    spot = '<path d="M206,0 L306,0 L470,512 L42,512 Z" fill="url(#%s-sp)"/>' % prefix
    return svg_open(512, 512) + '<defs>%s</defs>' % ''.join(defs) + bg + '<g clip-path="none">' + spot + '</g>' + body + '</svg>'


def c2_banner(prefix='c2b'):
    ids = Ids(prefix); defs = []
    sc, polys, width = c2_scene(grid=False)
    sc.fit((38, 26, 244, 102), align='bottom')
    body = sc.render(ids, defs)
    fy = sc.P((0, 0, 0))[1]
    defs.append('<linearGradient id="%s-sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#08080A"/><stop offset="1" stop-color="#141216"/></linearGradient>' % prefix)
    defs.append('<linearGradient id="%s-pt" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#C9C9D0"/></linearGradient>' % prefix)
    t, tw = text_flat('PLAYER', 15, 160, 160, 'url(#%s-pt)' % prefix, gap=60)
    lines = ('<rect x="%.1f" y="151.5" width="34" height="1" fill="#FFB020"/><rect x="%.1f" y="151.5" width="34" height="1" fill="#FFB020"/>'
             % (160 - tw / 2 - 12 - 34, 160 + tw / 2 + 12))
    spot = ('<path d="M10,0 L50,0 L190,%.1f L80,%.1f Z" fill="#FFF1D0" opacity="0.07"/>' % (fy, fy) +
            '<path d="M270,0 L310,0 L240,%.1f L130,%.1f Z" fill="#FFF1D0" opacity="0.07"/>' % (fy, fy))
    floor = '<rect x="0" y="%.1f" width="320" height="%.1f" fill="#0B0A09"/><rect x="0" y="%.1f" width="320" height="0.6" fill="#FFB020" opacity="0.35"/>' % (fy, 180 - fy, fy)
    return (svg_open(320, 180) + '<defs>%s</defs>' % ''.join(defs) + '<rect width="320" height="180" fill="url(#%s-sky)"/>' % prefix
            + spot + floor + body + t + lines + '</svg>')


# =====================================================================================
# CONCEPT 3 — "Ар-деко": tall fluted numerals, steep worm's-eye view, sunburst, ziggurat.
# =====================================================================================
S3 = dict(W=48, s=16, c=10, ci=3, gap=10, W1=38, yri=0.58, term=8, yB=56, wt=0.92, base1=False, sv=13)
C3_LIGHT = (-0.25, 0.85, -0.55)


def c3_scene(eye=(0, -12, -330), target=(0, 78, 0), base=True, player=True, flutes=True):
    cam = Camera(eye, target)
    sc = Scene(cam, light=C3_LIGHT)
    G = numerals(S3)
    polys, width = layout('2160', G, S3['gap'])
    polys = translate(polys, -width / 2, 0)
    if base:
        hw = width / 2
        steps = [(hw + 34, -64, -40, -30, 70), (hw + 22, -40, -18, -20, 60), (hw + 12, -18, 0, -10, 50)]
        for i, (x, y0, y1, z0, z1) in enumerate(steps):
            sc.box3(-x, x, y0, y1, z0, z1, STONE, layer=1 + i, top=('lin', cam.proj((0, y1, z0)), cam.proj((0, y1, z1)), [(0, '#3A3020'), (0.3, '#17151A'), (1, '#0B0B0D')]),
                    front=('lin', cam.proj((0, y1, z0)), cam.proj((0, y0, z0)), [(0, '#2E2E36'), (0.12, '#17171C'), (1, '#09090B')]))
            sc.box3(-x, x, y1 - 1.6, y1, z0 - 0.5, z0, GOLD, layer=5 + i,
                    front=('lin', cam.proj((-x, y1, z0)), cam.proj((x, y1, z0)),
                           [(0, '#3B2303'), (0.3, '#C98410'), (0.5, '#FFE3A0'), (0.7, '#C98410'), (1, '#3B2303')]))
    sc.extrude(polys, z0=0, depth=34, bevel=2.2, mat=GOLD, front=front_grad(sc, 100, 0, GOLD_FRONT), side_fade=0.35,
               grid=((6.0, 0) if flutes else None), grid_color='#5A3300', grid_opacity=0.55, grid_width=None)
    if base and player:
        L = letters(62, 20, 12)
        lp, lw = layout('PLAYER', L, 40)
        k = 0.15
        lp = translate(lp, -lw * k / 2, -36.5, k)
        sc.extrude(lp, z0=-21.2, depth=1.2, bevel=0.5, mat=GOLD, layer_side=30, layer_front=40,
                   front=('lin', cam.proj((0, -21.5, -21)), cam.proj((0, -36.5, -21)),
                          [(0, '#FFF0C0'), (0.45, '#FFC040'), (0.55, '#C47A08'), (1, '#FFB020')]))
    return sc, width


def sunburst(defs, ids, cx, cy, r, n, prefix, op=1.0, c1='#3A2A0E', c2='#120E08'):
    gid = '%s-sb' % prefix
    defs.append('<radialGradient id="%s" gradientUnits="userSpaceOnUse" cx="%.1f" cy="%.1f" r="%.1f">'
                '<stop offset="0" stop-color="#FFB020" stop-opacity="0.55"/><stop offset="0.35" stop-color="#C47A08" stop-opacity="0.22"/>'
                '<stop offset="1" stop-color="#C47A08" stop-opacity="0"/></radialGradient>' % (gid, cx, cy, r))
    parts = []
    for i in range(n):
        a0 = math.pi + math.pi * (i + 0.18) / n
        a1 = math.pi + math.pi * (i + 0.82) / n
        parts.append('M%.1f,%.1f L%.1f,%.1f L%.1f,%.1f Z' % (cx, cy, cx + r * math.cos(a0), cy + r * math.sin(a0),
                                                           cx + r * math.cos(a1), cy + r * math.sin(a1)))
    return '<path d="%s" fill="url(#%s)" opacity="%.2f"/>' % (' '.join(parts), gid, op)


def c3_hero(prefix='c3h'):
    W, H = 1600, 900
    ids = Ids(prefix); defs = []
    sc, width = c3_scene()
    sc.fit((330, 90, 940, 730), align='bottom')
    body = sc.render(ids, defs)
    cx, cy = sc.P((0, 0, 60))
    defs.append('<linearGradient id="%s-sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#050506"/>'
                '<stop offset="0.7" stop-color="#0E0E10"/><stop offset="1" stop-color="#16120A"/></linearGradient>' % prefix)
    defs.append('<filter id="%s-blur" x="-50%%" y="-50%%" width="200%%" height="200%%"><feGaussianBlur stdDeviation="18"/></filter>' % prefix)
    sb = sunburst(defs, ids, cx, cy, 1000, 26, prefix, op=0.6)
    glow = '<ellipse cx="%.1f" cy="%.1f" rx="420" ry="200" fill="#FFB020" opacity="0.16" filter="url(#%s-blur)"/>' % (cx, cy - 120, prefix)
    fy = cy
    defs.append('<linearGradient id="%s-fl" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#1C160B"/><stop offset="1" stop-color="#050505"/></linearGradient>' % prefix)
    floor = '<rect x="0" y="%.1f" width="%d" height="%.1f" fill="url(#%s-fl)"/>' % (fy, W, H - fy, prefix)
    defs.append('<radialGradient id="%s-vig" cx="0.5" cy="0.5" r="0.75"><stop offset="0.55" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity="0.7"/></radialGradient>' % prefix)
    vig = '<rect width="%d" height="%d" fill="url(#%s-vig)"/>' % (W, H, prefix)
    return (svg_open(W, H) + '<defs>%s</defs>' % ''.join(defs) + '<rect width="%d" height="%d" fill="url(#%s-sky)"/>' % (W, H, prefix)
            + sb + glow + floor + body + vig + '</svg>')


def c3_icon(prefix='c3i'):
    ids = Ids(prefix); defs = []
    sc, width = c3_scene(eye=(0, -40, -300), target=(0, 60, 0), base=False, flutes=False)
    sc.fit((50, 120, 412, 260))
    body = sc.render(ids, defs)
    bg = icon_bg(prefix, defs, glow_y=0.78)
    cx, cy = sc.P((0, 0, 30))
    sb = sunburst(defs, ids, 256, cy + 4, 330, 14, prefix, op=0.9)
    x0, y0 = sc.P((-width / 2 - 10, 0, -4)); x1, _ = sc.P((width / 2 + 10, 0, -4))
    bar = '<rect x="%.1f" y="%.1f" width="%.1f" height="9" fill="#C98410"/><rect x="%.1f" y="%.1f" width="%.1f" height="3" fill="#FFE3A0"/>' % (
        x0, y0 + 6, x1 - x0, x0, y0 + 6, x1 - x0)
    return svg_open(512, 512) + '<defs>%s</defs>' % ''.join(defs) + bg + sb + body + bar + '</svg>'


def c3_banner(prefix='c3b'):
    ids = Ids(prefix); defs = []
    sc, width = c3_scene(flutes=False)
    sc.fit((54, 8, 212, 166), align='bottom')
    body = sc.render(ids, defs)
    cx, cy = sc.P((0, 0, 60))
    defs.append('<linearGradient id="%s-sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#060607"/><stop offset="1" stop-color="#16120A"/></linearGradient>' % prefix)
    sb = sunburst(defs, ids, cx, cy, 300, 22, prefix, op=0.55)
    return (svg_open(320, 180) + '<defs>%s</defs>' % ''.join(defs) + '<rect width="320" height="180" fill="url(#%s-sky)"/>' % prefix
            + sb + body + '</svg>')


def build(write):
    return {
        'c2': dict(hero=write('concept-2-premiere-hero.svg', c2_hero()),
                   icon=write('concept-2-premiere-icon.svg', c2_icon()),
                   banner=write('concept-2-premiere-tv-banner.svg', c2_banner())),
        'c3': dict(hero=write('concept-3-deco-hero.svg', c3_hero()),
                   icon=write('concept-3-deco-icon.svg', c3_icon()),
                   banner=write('concept-3-deco-tv-banner.svg', c3_banner())),
    }
