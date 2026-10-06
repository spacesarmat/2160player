# -*- coding: utf-8 -*-
"""2160 Player — One UI-style icon concepts. Pure SVG, no fonts, no filters in icon assets."""
import math, os, re, sys
from shapely.geometry import LineString, Polygon, Point
from shapely.ops import unary_union
from shapely import affinity

OUT = os.path.dirname(os.path.abspath(__file__))
S = 512
FG_SCALE = 72 / 108          # icon-space (visible 72dp) -> 108dp adaptive canvas

# =============================================================== geometry helpers
def arc(cx, cy, r, a0, a1, step=4, ry=None):
    ry = ry or r
    n = max(2, int(abs(a1 - a0) / step) + 1)
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / (n - 1))),
             cy + ry * math.sin(math.radians(a0 + (a1 - a0) * i / (n - 1)))) for i in range(n)]


def tangent_from(P, C, r, left=True):
    dx, dy = P[0] - C[0], P[1] - C[1]
    d = math.hypot(dx, dy)
    a = math.atan2(dy, dx)
    b = math.acos(r / d)
    t = a + b if left else a - b
    return (C[0] + r * math.cos(t), C[1] + r * math.sin(t)), math.degrees(t)


def polys_of(g):
    if g.is_empty: return []
    return [g] if g.geom_type == 'Polygon' else [p for p in g.geoms if p.geom_type == 'Polygon']


def pathd_polys(polys, fn):
    out = []
    for p in polys:
        for ring in [p.exterior] + list(p.interiors):
            pts = [fn(c) for c in list(ring.coords)[:-1]]
            out.append('M' + ' L'.join('%.2f,%.2f' % q for q in pts) + 'Z')
    return ' '.join(out)


def ptsd(pts):
    return 'M' + ' L'.join('%.2f,%.2f' % p for p in pts) + 'Z'


def squircle_pts(cx, cy, R, n=5.0, N=180):
    pts = []
    for i in range(N):
        t = 2 * math.pi * i / N
        c, s = math.cos(t), math.sin(t)
        x = R * math.copysign(abs(c) ** (2 / n), c)
        y = R * math.copysign(abs(s) ** (2 / n), s)
        pts.append((cx + x, cy + y))
    return pts


def rounded_tri(cx, cy, R, r, rot=0):
    """equilateral 'play' triangle pointing right, circumradius R, corner radius r (svg coords)"""
    pts = [(cx + R * math.cos(math.radians(a + rot)), cy + R * math.sin(math.radians(a + rot))) for a in (0, 120, 240)]
    return Polygon(pts).buffer(-r, join_style='mitre').buffer(r, quad_segs=16)


# =============================================================== rounded monoline type
# glyph space: cap height 100, baseline y=0, y up. each glyph -> (list of polylines, advance)
def G(ch):
    if ch == '2':
        a = arc(26, 73, 26, 168, -38)
        return [a + [(1, 0), (55, 0)]], 55
    if ch == '1':
        return [[(4, 80), (28, 100), (28, 0)]], 40
    if ch == '6':
        C, r = (27, 28), 27
        tp, ang = tangent_from((40, 100), C, r, left=True)
        loop = arc(C[0], C[1], r, ang, ang + 360, step=3)
        return [[(40, 100)] + loop], 54
    if ch == '0':
        return [arc(27, 50, 27, 0, 360, step=3, ry=50)], 54
    if ch == 'P':
        return [[(0, 0), (0, 100), (28, 100)] + arc(28, 75, 25, 90, -90) + [(0, 50)]], 56
    if ch == 'l':
        return [[(0, 0), (0, 104)]], 0
    if ch == 'a':
        return [arc(32, 35, 32, 0, 360, step=3), [(64, 70), (64, 0)]], 64
    if ch == 'y':
        return [[(0, 70), (31, 0)], [(62, 70), (16, -32)]], 62
    if ch == 'e':
        return [[(1, 35), (66, 35)] + arc(33.5, 35, 33.5, 0, 318, step=3)], 66
    if ch == 'r':
        return [[(0, 0), (0, 70)], [(0, 36)] + arc(34, 36, 34, 180, 92)], 40
    if ch == ' ':
        return [], 22
    raise KeyError(ch)


def text_polys(text, w=14, track=16):
    """returns shapely polys in glyph space and total width"""
    x = 0.0
    shapes = []
    for i, ch in enumerate(text):
        lines, adv = G(ch)
        for ln in lines:
            shapes.append(affinity.translate(LineString(ln).buffer(w / 2, quad_segs=12, cap_style='round', join_style='round'), x + w / 2, 0))
        x += adv + w + track
    x -= track
    return polys_of(unary_union(shapes)), x


def text_path(text, x0, base_y, cap, w=14, track=16, anchor='start'):
    polys, width = text_polys(text, w, track)
    k = cap / 100.0
    if anchor == 'middle': x0 -= width * k / 2
    d = pathd_polys(polys, lambda p: (x0 + p[0] * k, base_y - p[1] * k))
    return d, width * k


# =============================================================== svg helpers
class Defs:
    def __init__(self, prefix): self.prefix, self.items, self.n = prefix, [], 0
    def id(self, t='g'):
        self.n += 1; return '%s-%s%d' % (self.prefix, t, self.n)
    def lin(self, stops, x1=0, y1=0, x2=0, y2=1, user=False):
        i = self.id('l')
        u = ' gradientUnits="userSpaceOnUse"' if user else ''
        self.items.append('<linearGradient id="%s"%s x1="%s" y1="%s" x2="%s" y2="%s">%s</linearGradient>' % (i, u, x1, y1, x2, y2, st(stops)))
        return 'url(#%s)' % i
    def rad(self, stops, cx, cy, r, user=True):
        i = self.id('r')
        u = ' gradientUnits="userSpaceOnUse"' if user else ''
        self.items.append('<radialGradient id="%s"%s cx="%s" cy="%s" r="%s">%s</radialGradient>' % (i, u, cx, cy, r, st(stops)))
        return 'url(#%s)' % i
    def xml(self): return '<defs>%s</defs>' % ''.join(self.items)


def st(stops):
    o = []
    for s in stops:
        op = s[2] if len(s) > 2 else 1
        o.append('<stop offset="%s" stop-color="%s"%s/>' % (s[0], s[1], '' if op == 1 else ' stop-opacity="%s"' % op))
    return ''.join(o)


def svg(w, h, body, defs=None, vb=None):
    vb = vb or '0 0 %d %d' % (w, h)
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="%s" width="%d" height="%d">%s%s</svg>' % (
        vb, w, h, defs.xml() if defs else '', body)


def soft_shadow(d, dy, color, op, steps=4, spread=1.0):
    """fake soft drop shadow: stacked translucent offset copies (vector-drawable friendly)"""
    out = []
    for i in range(steps):
        k = (i + 1) / steps
        out.append('<path d="%s" fill="%s" opacity="%.3f" transform="translate(0,%.1f)"/>' % (d, color, op / steps, dy * k * spread))
    return ''.join(out)


# =============================================================== CONCEPTS
# each concept: bg(defs) -> svg body (full 512 square, full bleed); glyph(defs) -> body; mono() -> polys path d
AMBER_BG = [(0, '#FFCB45'), (0.48, '#FF9A1F'), (1, '#FF5A3C')]


# ---- A: layered glass play ---------------------------------------------------------
def A_bg(D):
    g = D.lin(AMBER_BG, 0, 0, 1, 1)
    hl = D.rad([(0, '#FFFFFF', 0.38), (1, '#FFFFFF', 0)], 150, 90, 300)
    return '<rect width="512" height="512" fill="%s"/><rect width="512" height="512" fill="%s"/>' % (g, hl)


def A_shapes():
    cx, cy = 286, 244
    front = rounded_tri(cx, cy, 142, 38)
    mid = affinity.translate(front, -50, 0)
    back = affinity.translate(front, -100, 0)
    return back, mid, front


def A_glyph(D, badge=True):
    back, mid, front = A_shapes()
    f = lambda p: p
    s = ''
    s += '<path d="%s" fill="#FFFFFF" opacity="0.26"/>' % pathd_polys([back], f)
    s += '<path d="%s" fill="#FFFFFF" opacity="0.42"/>' % pathd_polys([mid], f)
    fd = pathd_polys([front], f)
    s += soft_shadow(fd, 14, '#B83A10', 0.32)
    s += '<path d="%s" fill="%s"/>' % (fd, D.lin([(0, '#FFFFFF'), (1, '#FFE9CC')], 0, 0, 0.3, 1))
    # inner highlight on front layer
    s += '<path d="%s" fill="none" stroke="%s" stroke-width="4"/>' % (fd, D.lin([(0, '#FFFFFF', 0.9), (1, '#FFFFFF', 0)], 0, 0, 0.2, 1))
    if badge:
        bx, by, bw, bh = 316, 360, 156, 64
        pill = 'M%.1f,%.1f h%.1f a%.1f,%.1f 0 0 1 0,%.1f h-%.1f a%.1f,%.1f 0 0 1 0,-%.1fZ' % (
            bx - bw / 2 + bh / 2, by - bh / 2, bw - bh, bh / 2, bh / 2, bh, bw - bh, bh / 2, bh / 2, bh)
        s += soft_shadow(pill, 8, '#8A2A0A', 0.30)
        s += '<path d="%s" fill="%s"/>' % (pill, D.lin([(0, '#3B2C86'), (1, '#1E1650')]))
        d, _ = text_path('2160', bx, by + 15, 30, w=19, track=10, anchor='middle')
        s += '<path d="%s" fill="#FFFFFF"/>' % d
    return s


def A_mono():
    back, mid, front = A_shapes()
    g = unary_union([front, mid.difference(front.buffer(12)), back.difference(mid.buffer(12))])
    return pathd_polys(polys_of(g), lambda p: p)


# ---- B: 21/60 monogram on glass card ---------------------------------------------------
B_BG = [(0, '#FFB52B'), (1, '#FF5E36')]


def B_bg(D):
    g = D.lin(B_BG, 0, 0, 0.35, 1)
    hl = D.rad([(0, '#FFFFFF', 0.30), (1, '#FFFFFF', 0)], 160, 70, 320)
    return '<rect width="512" height="512" fill="%s"/><rect width="512" height="512" fill="%s"/>' % (g, hl)


def B_digits():
    cap, w = 122, 35
    p1, w1 = text_polys('21', w=w * 100 / cap, track=16)
    p2, w2 = text_polys('60', w=w * 100 / cap, track=10)
    k = cap / 100
    top_base = 240; bot_base = 240 + 30 + cap
    f1 = lambda p: (256 - w1 * k / 2 + p[0] * k - 4, top_base - p[1] * k)
    f2 = lambda p: (256 - w2 * k / 2 + p[0] * k, bot_base - p[1] * k)
    return pathd_polys(p1, f1) + ' ' + pathd_polys(p2, f2)


def B_glyph(D):
    s = ''
    card = 'M150,92 h212 a62,62 0 0 1 62,62 v204 a62,62 0 0 1 -62,62 h-212 a62,62 0 0 1 -62,-62 v-204 a62,62 0 0 1 62,-62Z'
    s += '<path d="%s" fill="%s"/>' % (card, D.lin([(0, '#FFFFFF', 0.30), (1, '#FFFFFF', 0.08)], 0, 0, 0.4, 1))
    s += '<path d="%s" fill="none" stroke="%s" stroke-width="3"/>' % (card, D.lin([(0, '#FFFFFF', 0.75), (0.5, '#FFFFFF', 0.12), (1, '#FFFFFF', 0.3)], 0, 0, 1, 1))
    d = B_digits()
    s += soft_shadow(d, 12, '#A8340E', 0.34)
    s += '<path d="%s" fill="%s"/>' % (d, D.lin([(0, '#FFFFFF'), (1, '#FFEBD2')]))
    return s


def B_mono():
    return B_digits()


# ---- C: glass screen + amber play on deep violet ------------------------------------------
def C_bg(D):
    g = D.lin([(0, '#4A3AB0'), (0.55, '#2A2172'), (1, '#160F3E')], 0, 0, 0.4, 1)
    hl = D.rad([(0, '#9C8CFF', 0.35), (1, '#9C8CFF', 0)], 130, 60, 330)
    glow = D.rad([(0, '#FF9A1F', 0.45), (1, '#FF9A1F', 0)], 256, 330, 260)
    return '<rect width="512" height="512" fill="%s"/><rect width="512" height="512" fill="%s"/><rect width="512" height="512" fill="%s"/>' % (g, hl, glow)


def C_parts():
    screen = Polygon([(98, 138), (414, 138), (414, 352), (98, 352)]).buffer(-40, join_style='mitre').buffer(40, quad_segs=16)
    play = rounded_tri(270, 245, 100, 26)
    stand = Polygon([(206, 384), (306, 384), (306, 384.1), (206, 384.1)]).buffer(11, quad_segs=12)
    return screen, play, stand


def C_glyph(D):
    screen, play, stand = C_parts()
    f = lambda p: p
    sd = pathd_polys([screen], f)
    s = soft_shadow(sd, 14, '#08051C', 0.45)
    s += '<path d="%s" fill="%s"/>' % (sd, D.lin([(0, '#FFFFFF', 0.30), (1, '#FFFFFF', 0.10)], 0, 0, 0.3, 1))
    s += '<path d="%s" fill="none" stroke="%s" stroke-width="3.5"/>' % (sd, D.lin([(0, '#FFFFFF', 0.8), (0.6, '#FFFFFF', 0.15), (1, '#FFFFFF', 0.35)], 0, 0, 1, 1))
    s += '<path d="%s" fill="#FFFFFF" opacity="0.5"/>' % pathd_polys([stand], f)
    pd = pathd_polys([play], f)
    s += soft_shadow(pd, 10, '#2A0E3A', 0.40)
    s += '<path d="%s" fill="%s"/>' % (pd, D.lin([(0, '#FFD45A'), (0.55, '#FF9A1F'), (1, '#FF5A3C')], 0, 0, 0.5, 1))
    s += '<path d="%s" fill="none" stroke="%s" stroke-width="3"/>' % (pd, D.lin([(0, '#FFFFFF', 0.8), (1, '#FFFFFF', 0)], 0, 0, 0.2, 1))
    return s


def C_mono():
    screen, play, stand = C_parts()
    g = unary_union([screen.difference(screen.buffer(-16)), play, stand])
    return pathd_polys(polys_of(g), lambda p: p)


CONCEPTS = {
    'A': dict(slug='layers', name='Слои', bg=A_bg, glyph=A_glyph, mono=A_mono,
              desc='Три полупрозрачных «стеклянных» треугольника Play, наложенных друг на друга, как кадры при перемотке, '
                   'на тёплом градиенте амбер → коралл. Внизу справа — тёмно-синий бейдж «2160». Самый «самсунговский»: '
                   'простая дружелюбная форма, слои с глубиной, мягкие тени.'),
    'B': dict(slug='monogram', name='Монограмма 21/60', bg=B_bg, glyph=B_glyph, mono=B_mono,
              desc='Крупные округлые цифры 21 / 60 в две строки на стеклянной карточке поверх градиента амбер → оранжевый. '
                   'Прямо называет бренд и отлично читается на 48 px; продолжает идею монограммы из v2, но в мягком стиле One UI.'),
    'C': dict(slug='screen', name='Экран', bg=C_bg, glyph=C_glyph, mono=C_mono,
              desc='Стеклянный экран с янтарным Play на глубоком фиолетово-синем фоне, снизу — подставка и тёплое свечение. '
                   'Контрастный, «кинотеатральный» вариант, который выделяется среди светлых иконок.'),
}


# =============================================================== assets
def full_icon(key, prefix=None, shape='squircle'):
    c = CONCEPTS[key]; D = Defs(prefix or 'i' + key)
    clip = D.id('c')
    if shape == 'squircle': sh = ptsd(squircle_pts(256, 256, 256))
    elif shape == 'circle': sh = 'M0,256 a256,256 0 1 0 512,0 a256,256 0 1 0 -512,0Z'
    else: sh = 'M0,0 H512 V512 H0Z'
    D.items.append('<clipPath id="%s"><path d="%s"/></clipPath>' % (clip, sh))
    body = '<g clip-path="url(#%s)">%s%s</g>' % (clip, c['bg'](D), c['glyph'](D))
    return svg(512, 512, body, D)


def fg_transform(inner):
    k = FG_SCALE
    return '<g transform="translate(%.3f,%.3f) scale(%.5f)">%s</g>' % (256 - 256 * k, 256 - 256 * k, k, inner)


def adaptive_fg(key):
    c = CONCEPTS[key]; D = Defs('f' + key)
    return svg(512, 512, fg_transform(c['glyph'](D)), D)


def adaptive_bg(key):
    c = CONCEPTS[key]; D = Defs('b' + key)
    # the background is shown at the same 72dp window: scale gradients with the same transform, but fill full bleed
    k = FG_SCALE
    inner = c['bg'](D).replace('width="512" height="512"', 'x="-128" y="-128" width="768" height="768"')
    return svg(512, 512, fg_transform(inner), D)


def adaptive_mono(key):
    c = CONCEPTS[key]
    return svg(512, 512, fg_transform('<path d="%s" fill="#FFFFFF" fill-rule="nonzero"/>' % c['mono']()))


def wordmark(x, base, cap, dark_bg=True, D=None):
    d1, w1 = text_path('2160', x, base, cap, w=17, track=9)
    gap = cap * 0.30
    d2, w2 = text_path('Player', x + w1 + gap, base, cap, w=12, track=12)
    g = D.lin([(0, '#FFC93C'), (1, '#FF6A3D')], 0, 0, 1, 1) if D else '#FF9A1F'
    col = '#F2F2F2' if dark_bg else '#17171A'
    return '<path d="%s" fill="%s"/><path d="%s" fill="%s"/>' % (d1, g, d2, col), w1 + gap + w2


def mini_icon(key, x, y, size, prefix):
    s = full_icon(key, prefix)
    inner = re.sub(r'^<svg[^>]*>', '', s)[:-6]
    return '<g transform="translate(%.2f,%.2f) scale(%.5f)">%s</g>' % (x, y, size / 512, inner)


def header(key, dark=True):
    D = Defs('h%s%d' % (key, dark))
    icon = 150; cap = 74; pad = 6
    wm, ww = wordmark(icon + 40, pad + icon / 2 + cap / 2 + 4, cap, dark, D)
    W = int(icon + 40 + ww + pad * 2 + 8); H = icon + pad * 2
    body = mini_icon(key, pad, pad, icon, 'hm%s%d' % (key, dark)) + wm
    return svg(W, H, body, D), W, H


def banner(key):
    D = Defs('t' + key)
    bg = D.lin([(0, '#1E1A3A'), (1, '#0E0E10')], 0, 0, 1, 1)
    icon = 76; cap = 23
    _, ww = wordmark(0, 0, cap, True, None)
    total = icon + 22 + ww
    x0 = (320 - total) / 2
    glow = D.rad([(0, '#FF9A1F', 0.30), (1, '#FF9A1F', 0)], x0 + icon / 2, 90, 140)
    wm, _ = wordmark(x0 + icon + 22, 90 + cap / 2, cap, True, D)
    body = ('<rect width="320" height="180" fill="%s"/><rect width="320" height="180" fill="%s"/>' % (bg, glow)
            + mini_icon(key, x0, 90 - icon / 2, icon, 'tm' + key) + wm)
    return svg(320, 180, body, D)


# =============================================================== mock system icons (placeholders)
def mock(kind, prefix):
    D = Defs(prefix)
    sq = ptsd(squircle_pts(256, 256, 256))
    clip = D.id('c'); D.items.append('<clipPath id="%s"><path d="%s"/></clipPath>' % (clip, sq))
    s = ''
    if kind == 'phone':
        s += '<rect width="512" height="512" fill="%s"/>' % D.lin([(0, '#6BE07A'), (1, '#1FAF4E')])
        h = LineString(arc(256, 256, 120, 110, 200)).buffer(42, cap_style='round')
        s += '<path d="%s" fill="#FFFFFF" transform="rotate(-20 256 256) translate(40,-10)"/>' % pathd_polys(polys_of(h), lambda p: p)
    elif kind == 'msg':
        s += '<rect width="512" height="512" fill="%s"/>' % D.lin([(0, '#5BB4FF'), (1, '#2264F0')])
        b = unary_union([Point(256, 240).buffer(132, quad_segs=24), Polygon([(150, 300), (130, 380), (220, 340)]).buffer(10)])
        b = affinity.scale(b, 1.05, 0.88, origin=(256, 250))
        s += '<path d="%s" fill="#FFFFFF"/>' % pathd_polys(polys_of(b), lambda p: p)
    elif kind == 'camera':
        s += '<rect width="512" height="512" fill="%s"/>' % D.lin([(0, '#4A4A52'), (1, '#1C1C22')])
        s += '<circle cx="256" cy="256" r="150" fill="#0E0E12"/><circle cx="256" cy="256" r="150" fill="none" stroke="#8C8C96" stroke-width="10"/>'
        s += '<circle cx="256" cy="256" r="90" fill="%s"/>' % D.rad([(0, '#5A7CFF'), (1, '#121A3A')], 230, 230, 110)
        s += '<circle cx="225" cy="222" r="22" fill="#FFFFFF" opacity="0.7"/>'
    elif kind == 'gallery':
        s += '<rect width="512" height="512" fill="%s"/>' % D.lin([(0, '#FFFFFF'), (1, '#F1EEF3')])
        for ang, col in [(0, '#FF4F7B'), (120, '#FF9A3C'), (240, '#B04BFF')]:
            e = affinity.rotate(affinity.scale(Point(256, 196).buffer(80, quad_segs=24), 0.8, 1.25, origin=(256, 196)), ang, origin=(256, 256))
            s += '<path d="%s" fill="%s" opacity="0.78"/>' % (pathd_polys([e], lambda p: p), col)
    elif kind == 'weather':
        s += '<rect width="512" height="512" fill="%s"/>' % D.lin([(0, '#5EC8FF'), (1, '#2A7BE8')])
        s += '<circle cx="300" cy="200" r="84" fill="%s"/>' % D.lin([(0, '#FFE16B'), (1, '#FFB020')])
        cl = unary_union([Point(210, 320).buffer(70), Point(290, 290).buffer(88), Point(370, 330).buffer(58),
                          Polygon([(160, 330), (400, 330), (400, 388), (160, 388)]).buffer(0)])
        cl = cl.buffer(-10).buffer(10)
        s += '<path d="%s" fill="#FFFFFF" opacity="0.95"/>' % pathd_polys(polys_of(cl), lambda p: p)
    elif kind == 'clock':
        s += '<rect width="512" height="512" fill="%s"/>' % D.lin([(0, '#3A3A44'), (1, '#16161C')])
        s += '<circle cx="256" cy="256" r="160" fill="#FFFFFF"/>'
        s += '<path d="M256,256 L256,150" stroke="#1C1C22" stroke-width="22" stroke-linecap="round"/>'
        s += '<path d="M256,256 L330,300" stroke="#FF5A3C" stroke-width="16" stroke-linecap="round"/><circle cx="256" cy="256" r="16" fill="#1C1C22"/>'
    elif kind == 'calendar':
        s += '<rect width="512" height="512" fill="%s"/>' % D.lin([(0, '#FFFFFF'), (1, '#EDEDF2')])
        s += '<rect x="120" y="128" width="272" height="260" rx="52" fill="%s"/>' % D.lin([(0, '#5B8CFF'), (1, '#3A5BE0')])
        s += '<rect x="150" y="200" width="212" height="160" rx="30" fill="#FFFFFF"/>'
        s += '<circle cx="210" cy="250" r="16" fill="#3A5BE0"/><circle cx="256" cy="250" r="16" fill="#C9D3F5"/><circle cx="302" cy="250" r="16" fill="#C9D3F5"/>'
        s += '<circle cx="210" cy="306" r="16" fill="#C9D3F5"/><circle cx="256" cy="306" r="16" fill="#C9D3F5"/>'
    return svg(512, 512, '<g clip-path="url(#%s)">%s</g>' % (clip, s), D)


# =============================================================== preview
def uniq(s, tag):
    for i in sorted(set(re.findall(r'id="([^"]+)"', s)), key=len, reverse=True):
        s = s.replace('id="%s"' % i, 'id="%s-%s"' % (i, tag)).replace('url(#%s)' % i, 'url(#%s-%s)' % (i, tag))
    return s


_n = [0]
def inst(s, w, h=None):
    _n[0] += 1
    h = h or w
    s = uniq(s, 'u%d' % _n[0])
    return re.sub(r'width="\d+" height="\d+"', 'width="%d" height="%d"' % (w, h), s, count=1)


def adaptive_masked(key, shape):
    """composite of adaptive layers seen through a launcher mask (72dp window)"""
    fg = re.sub(r'^<svg[^>]*>', '', adaptive_fg(key))[:-6]
    bg = re.sub(r'^<svg[^>]*>', '', adaptive_bg(key))[:-6]
    o = 256 - 256 * FG_SCALE; w = 512 * FG_SCALE
    if shape == 'circle': m = '<circle cx="256" cy="256" r="%.2f"/>' % (w / 2)
    else: m = '<path d="%s"/>' % ptsd(squircle_pts(256, 256, w / 2))
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="%.2f %.2f %.2f %.2f" width="512" height="512">'
            '<defs><clipPath id="am%s%s">%s</clipPath></defs><g clip-path="url(#am%s%s)">%s%s</g></svg>'
            % (o, o, w, w, key, shape, m, key, shape, bg, fg))


def themed(key, bgc, fgc):
    mono = re.sub(r'^<svg[^>]*>', '', adaptive_mono(key))[:-6].replace('#FFFFFF', fgc)
    o = 256 - 256 * FG_SCALE; w = 512 * FG_SCALE
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="%.2f %.2f %.2f %.2f" width="512" height="512">'
            '<path d="%s" fill="%s"/>%s</svg>' % (o, o, w, w, ptsd(squircle_pts(256, 256, w / 2)), bgc, mono))


MOCKS = [('phone', 'Телефон'), ('msg', 'Сообщения'), ('camera', 'Камера'), ('gallery', 'Галерея'),
         ('weather', 'Погода'), ('calendar', 'Календарь'), ('clock', 'Часы')]


def home_row(key, dark):
    cells = []
    items = MOCKS[:3] + [('__me', '2160 Player')] + MOCKS[3:]
    for k, label in items:
        icon = inst(full_icon(key), 64) if k == '__me' else inst(mock(k, 'm' + k), 64)
        cells.append('<div class="app">%s<span>%s</span></div>' % (icon, label))
    return '<div class="home %s">%s</div>' % ('wdark' if dark else 'wlight', ''.join(cells))


def preview(files):
    secs = []
    for key, c in CONCEPTS.items():
        fi = full_icon(key)
        sizes = ''.join('<div class="cell">%s<span>%d</span></div>' % (inst(fi, n), n) for n in (192, 96, 48, 24))
        masks = ''.join('<div class="cell">%s<span>%s</span></div>' % (inst(adaptive_masked(key, sh), 150), lab)
                        for sh, lab in [('circle', 'адаптивная: круг'), ('squircle', 'адаптивная: сквиркл')])
        th = ''.join('<div class="cell">%s<span>%s</span></div>' % (inst(themed(key, b, f), 96), lab)
                     for b, f, lab in [('#E9DDFF', '#3B2C86', 'тема: светлая'), ('#2A2440', '#D8CCFF', 'тема: тёмная'),
                                       ('#FFE0C2', '#6A3300', 'тема: тёплая')])
        hd, hw, hh = header(key, True); hl, lw, lh = header(key, False)
        secs.append('''
<section>
 <h2>Концепт %s — «%s»</h2><p class="desc">%s</p>
 <div class="row top"><div class="big">%s</div>
  <div class="col">
   <div class="row">%s</div>
   <div class="row">%s%s</div>
  </div></div>
 <h3>Главный экран (обои тёмные / светлые)</h3>
 %s%s
 <h3>Шапка приложения и TV-баннер</h3>
 <div class="row"><div class="hdr dark">%s</div><div class="hdr light">%s</div><div class="cell">%s<span>TV-баннер 320×180</span></div></div>
</section>''' % (key, c['name'], c['desc'], inst(fi, 300), sizes, masks, th,
                 home_row(key, True), home_row(key, False),
                 inst(hd, int(hw * 0.6), int(hh * 0.6)), inst(hl, int(lw * 0.6), int(lh * 0.6)), inst(banner(key), 320, 180)))
    html = '''<!doctype html><html lang="ru"><head><meta charset="utf-8"><title>2160 Player — иконка в стиле One UI</title>
<style>
body{margin:0;background:#0E0E10;color:#F2F2F2;font:15px/1.5 "Segoe UI",system-ui,sans-serif}
header{padding:26px 40px 4px}h1{margin:0;font-size:25px}header p{color:#9a9aa2;margin:6px 0 0;max-width:1100px}
section{padding:24px 40px 30px;border-top:1px solid #222}
h2{margin:0 0 4px;color:#FFB020;font-size:22px}h3{margin:20px 0 10px;font-size:13px;color:#999;font-weight:500;text-transform:uppercase;letter-spacing:.08em}
.desc{max-width:1100px;margin:0 0 14px;color:#d6d6da}
.row{display:flex;gap:24px;align-items:flex-end;flex-wrap:wrap}
.top{align-items:center;gap:40px}.col{display:flex;flex-direction:column;gap:20px}
.cell{display:flex;flex-direction:column;align-items:center;gap:6px;font-size:12px;color:#888}
.home{display:flex;gap:22px;padding:26px 30px;border-radius:24px;margin-bottom:12px;width:fit-content}
.wdark{background:radial-gradient(circle at 15% 20%,#3B2C86 0,transparent 45%),radial-gradient(circle at 85% 90%,#7A2E3A 0,transparent 50%),#121018}
.wlight{background:radial-gradient(circle at 20% 10%,#FFE2C4 0,transparent 50%),radial-gradient(circle at 90% 80%,#D9E4FF 0,transparent 55%),#F4F1F6}
.app{display:flex;flex-direction:column;align-items:center;gap:7px;width:76px;font-size:12px;text-align:center}
.wdark .app{color:#fff}.wlight .app{color:#222}
.hdr{padding:18px 24px;border-radius:14px}.hdr.dark{background:#0E0E10;border:1px solid #2a2a2e}.hdr.light{background:#F7F7F9}
</style></head><body>
<header><h1>2160 Player — иконка в стиле One UI</h1>
<p>Сквиркл, одна крупная дружелюбная форма, мягкие многоцветные градиенты, полупрозрачные «стеклянные» слои и мягкие тени вместо фасок. Всё построено из собственных контуров (без шрифтов); в иконках нет фильтров — их можно перевести в VectorDrawable.</p></header>
__SECS__</body></html>'''.replace('__SECS__', ''.join(secs))
    with open(os.path.join(OUT, 'preview.html'), 'w', encoding='utf-8') as f: f.write(html)


def write(name, s):
    with open(os.path.join(OUT, name), 'w', encoding='utf-8') as f: f.write(s)


def build():
    for key, c in CONCEPTS.items():
        n = 'concept-%s-%s' % (key, c['slug'])
        write(n + '-icon.svg', full_icon(key))
        write(n + '-foreground.svg', adaptive_fg(key))
        write(n + '-background.svg', adaptive_bg(key))
        write(n + '-monochrome.svg', adaptive_mono(key))
        write(n + '-header-dark.svg', header(key, True)[0])
        write(n + '-header-light.svg', header(key, False)[0])
        write(n + '-tv-banner.svg', banner(key))
    preview(None)


if __name__ == '__main__':
    build()
