# -*- coding: utf-8 -*-
"""Original geometric numerals / letters (cap height 100, y up). No fonts."""
import math
from shapely.geometry import Polygon, box
from shapely.ops import unary_union
from engine import cr, clean, translate


# ------------------------------------------------------------------ numerals
def g0(W, s, c, ci, sv=None):
    sv = sv or s
    return clean(cr(0, 0, W, 100, c).difference(cr(s, sv, W - s, 100 - sv, ci)))


def g1(W, s, c, base=True, flag=0.78):
    bh = s * 0.82 if base else 0
    sx = (W - s) / 2 + (s * 0.12 if base else W * 0.18)
    if not base: sx = W - s
    stem = box(sx, 0, sx + s, 100)
    t = s * 0.95
    fd = sx * flag
    tip = 0 if base else 0
    fl = Polygon([(sx + 0.5, 100), (tip, 100 - fd), (tip, 100 - fd - t), (sx + 0.5, 100 - t)])
    parts = [stem, fl]
    if base: parts.append(box(0, 0, W, bh))
    g = unary_union(parts)
    return clean(g)


def g2(W, s, c, ci, yri=0.56, term=10, sv=None):
    sv = sv or s
    t = s * 1.02
    A = (W - s, 100 * yri)
    B = (0, sv + 3)
    dx, dy = A[0] - B[0], A[1] - B[1]
    L = math.hypot(dx, dy)
    nx, ny = dy / L, -dx / L
    P0 = (B[0] + nx * t * 1.0, B[1] + ny * t * 1.0)
    k = (sv - P0[1]) / dy
    pL = (P0[0] + k * dx, sv)
    k = (W - P0[0]) / dx
    pR = (W, P0[1] + k * dy)
    yT = 100 - sv - term
    pts = [(0, 0), (W, 0), (W, sv), pL, pR, (W, 100 - c), (W - c, 100), (c, 100), (0, 100 - c),
           (0, yT), (s, yT), (s, 100 - sv), (W - s - ci, 100 - sv), (W - s, 100 - sv - ci), A, B]
    return clean(Polygon(pts))


def g6(W, s, c, ci, yB=60, wt=0.86, sv=None):
    sv = sv or s
    Wt = W * wt
    outer = Polygon([(c, 0), (W - c, 0), (W, c), (W, yB - c), (W - c, yB), (s, yB), (s, 100 - sv),
                     (Wt, 100 - sv), (Wt, 100), (c, 100), (0, 100 - c), (0, c)])
    return clean(outer.difference(cr(s, sv, W - s, yB - sv, ci)))


def numerals(style):
    """returns dict digit -> (polys, advance)"""
    W, s, c, ci, gap = style['W'], style['s'], style['c'], style['ci'], style['gap']
    sv = style.get('sv')
    W1 = style.get('W1', W * 0.78)
    return {
        '2': (g2(W, s, c, ci, yri=style.get('yri', 0.56), term=style.get('term', 10), sv=sv), W),
        '1': (g1(W1, s, c, base=style.get('base1', True)), W1),
        '6': (g6(W, s, c, ci, yB=style.get('yB', 60), wt=style.get('wt', 0.86), sv=sv), W),
        '0': (g0(W, s, c, ci, sv=sv), W),
    }


def layout(text, glyphs, gap, k=1.0, x0=0.0, y0=0.0):
    out = []
    x = 0.0
    for ch in text:
        if ch == ' ':
            x += gap * 3; continue
        p, adv = glyphs[ch]
        out += translate(p, x, 0)
        x += adv + gap
    width = x - gap
    return translate(out, x0, y0, 1.0) if k == 1.0 else translate(out, x0, y0, k), width


def flute(polys, xs_list, w):
    """cut thin vertical grooves (art-deco fluting)"""
    from shapely.geometry import box as B
    cut = unary_union([B(x - w / 2, -10, x + w / 2, 110) for x in xs_list])
    g = unary_union(polys).difference(cut)
    return clean(g)


# ------------------------------------------------------------------ letters
def letters(W=62, s=19, c=12):
    H = 100
    yb = 42
    P_ = clean(unary_union([box(0, 0, s, H),
                            cr(0, yb, W, H, c, '0110').difference(box(s, yb + s * 0.9, W - s, H - s * 0.9))]))
    L_ = clean(unary_union([box(0, 0, s, H), box(0, 0, W * 0.86, s * 0.9)]))
    yA = 36
    A_ = clean(Polygon([(0, 0), (s, 0), (s, yA), (W - s, yA), (W - s, 0), (W, 0), (W, H - c * 1.6), (W - c * 1.6, H),
                        (c * 1.6, H), (0, H - c * 1.6)]).difference(box(s, yA + s * 0.9, W - s, H - s * 0.9)))
    yU = 44
    Y_ = clean(unary_union([
        Polygon([(0, H), (0, yU + c), (c, yU), (W - c, yU), (W, yU + c), (W, H), (W - s, H), (W - s, yU + s * 0.9),
                 (s, yU + s * 0.9), (s, H)]),
        box(W / 2 - s / 2, 0, W / 2 + s / 2, yU + 1)]))
    E_ = clean(unary_union([box(0, 0, s, H), box(0, 0, W * 0.9, s * 0.9), box(0, H - s * 0.9, W * 0.9, H),
                            box(0, (H - s * 0.9) / 2, W * 0.74, (H + s * 0.9) / 2)]))
    leg = Polygon([(W - s * 1.15, 0), (W, 0), (W * 0.42 + s * 1.05, yb + 1), (W * 0.42, yb + 1)])
    R_ = clean(unary_union([P_[0], leg]))
    return {'P': (P_, W), 'L': (L_, W * 0.86), 'A': (A_, W), 'Y': (Y_, W), 'E': (E_, W * 0.9), 'R': (R_, W)}
