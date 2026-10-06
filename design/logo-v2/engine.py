# -*- coding: utf-8 -*-
"""Tiny perspective 'extrusion' engine that emits SVG.

World: X right, Y up, Z away from the viewer. Glyphs live in the plane Z=0
(front face) and are extruded towards +Z. One camera, one light -> every
extrusion face converges to the same vanishing points and is shaded
consistently.
"""
import math
from shapely.geometry import Polygon, MultiPolygon, box, LineString
from shapely.geometry.polygon import orient
from shapely.ops import unary_union

# ----------------------------------------------------------------- vectors
def add(a, b): return (a[0]+b[0], a[1]+b[1], a[2]+b[2])
def sub(a, b): return (a[0]-b[0], a[1]-b[1], a[2]-b[2])
def mul(a, k): return (a[0]*k, a[1]*k, a[2]*k)
def dot(a, b): return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]
def cross(a, b): return (a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])
def norm(a):
    l = math.sqrt(dot(a, a)) or 1.0
    return (a[0]/l, a[1]/l, a[2]/l)

# ----------------------------------------------------------------- colours
def hx(c):
    c = c.lstrip('#'); return tuple(int(c[i:i+2], 16) for i in (0, 2, 4))
def tohex(rgb): return '#%02X%02X%02X' % tuple(max(0, min(255, int(round(v)))) for v in rgb)
def lerp(a, b, t): return tuple(a[i]+(b[i]-a[i])*t for i in range(3))

def ramp(stops):
    st = [(p, hx(c)) for p, c in stops]
    def f(t):
        t = max(0.0, min(1.0, t))
        for (p0, c0), (p1, c1) in zip(st, st[1:]):
            if t <= p1:
                return tohex(lerp(c0, c1, (t-p0)/(p1-p0) if p1 > p0 else 0))
        return tohex(st[-1][1])
    return f

GOLD = ramp([(0, '#120A00'), (0.22, '#3B2303'), (0.45, '#87560B'), (0.66, '#D88E12'),
             (0.80, '#FFB020'), (0.92, '#FFD978'), (1, '#FFF4D6')])
SILVER = ramp([(0, '#0B0B0D'), (0.25, '#2A2A2E'), (0.5, '#6B6B72'), (0.72, '#B9B9C0'),
               (0.88, '#E6E6EA'), (1, '#FFFFFF')])
AMBERR = ramp([(0, '#140900'), (0.3, '#4A2600'), (0.55, '#A35F00'), (0.78, '#F09A10'),
               (0.92, '#FFB020'), (1, '#FFE2A0')])

# ----------------------------------------------------------------- shapes
def cr(x0, y0, x1, y1, c=0.0, corners='1111'):
    """Chamfered rect. corner flags: bl, br, tr, tl."""
    c = min(c, (x1 - x0) * 0.45, (y1 - y0) * 0.45)
    cs = [c if f == '1' else 0 for f in corners]
    p = []
    p += [(x0, y0+cs[0]), (x0+cs[0], y0)] if cs[0] else [(x0, y0)]
    p += [(x1-cs[1], y0), (x1, y0+cs[1])] if cs[1] else [(x1, y0)]
    p += [(x1, y1-cs[2]), (x1-cs[2], y1)] if cs[2] else [(x1, y1)]
    p += [(x0+cs[3], y1), (x0, y1-cs[3])] if cs[3] else [(x0, y1)]
    return Polygon(p)

def polys(g):
    if g.is_empty: return []
    if isinstance(g, Polygon): return [g]
    return [p for p in getattr(g, 'geoms', []) if isinstance(p, Polygon) and not p.is_empty]

def clean(g):
    out = []
    for p in polys(g.buffer(0)):
        p = orient(p.simplify(0.01, preserve_topology=True), 1.0)
        out.append(p)
    return out

def translate(polylist, dx, dy, k=1.0):
    from shapely import affinity
    return [affinity.translate(affinity.scale(p, k, k, origin=(0, 0)), dx, dy) for p in polylist]

# ----------------------------------------------------------------- camera
class Camera:
    def __init__(self, eye, target, up=(0, 1, 0)):
        self.eye = eye
        self.f = norm(sub(target, eye))
        self.r = norm(cross(up, self.f))
        self.u = cross(self.f, self.r)

    def depth(self, p):
        return dot(sub(p, self.eye), self.f)

    def proj(self, p):
        d = sub(p, self.eye)
        z = dot(d, self.f)
        return (dot(d, self.r)/z, -dot(d, self.u)/z)

    def faces_cam(self, normal, point):
        return dot(normal, sub(point, self.eye)) < 0

# ----------------------------------------------------------------- scene
class Scene:
    """Collects items with *raw* projected coordinates; finalised by an affine fit."""
    def __init__(self, cam, light=(-0.45, 0.62, -0.64)):
        self.cam = cam
        self.L = norm(light)
        self.items = []          # (layer, depth, dict)
        self.subject = []        # raw points used for fitting

    # ---- low level
    def poly3(self, rings3, fill, layer=0, depth=None, subj=True, **attrs):
        rings = [[self.cam.proj(p) for p in r] for r in rings3]
        if depth is None:
            pts = [p for r in rings3 for p in r]
            c = mul(pts[0], 0)
            for p in pts: c = add(c, p)
            depth = self.cam.depth(mul(c, 1.0/len(pts)))
        if subj: self.subject += [p for r in rings for p in r]
        self.items.append((layer, -depth, dict(rings=rings, fill=fill, **attrs)))

    def shade(self, n, lo=0.10, k=0.95):
        return lo + k*max(0.0, dot(n, self.L))

    # ---- extrusion of planar 2D polygons (XY plane at z=z0, extruded to z0+depth)
    def extrude(self, polylist, z0=0.0, depth=30.0, bevel=2.5, mat=GOLD, front=None,
                layer_side=10, layer_front=20, side_fade=0.45, subj=True, side_boost=1.0,
                grid=None, grid_color='#000000', grid_opacity=0.18, grid_width=None, edge=None):
        cam = self.cam
        for poly in polylist:
            rings = [list(poly.exterior.coords)[:-1]] + [list(i.coords)[:-1] for i in poly.interiors]
            fronts = []
            for ring in rings:
                n = len(ring)
                inset = []
                for i in range(n):
                    p0, p1, p2 = ring[i-1], ring[i], ring[(i+1) % n]
                    e1 = (p1[0]-p0[0], p1[1]-p0[1]); e2 = (p2[0]-p1[0], p2[1]-p1[1])
                    l1 = math.hypot(*e1) or 1; l2 = math.hypot(*e2) or 1
                    n1 = (-e1[1]/l1, e1[0]/l1); n2 = (-e2[1]/l2, e2[0]/l2)   # left = into material
                    d = 1 + n1[0]*n2[0] + n1[1]*n2[1]
                    off = ((n1[0]+n2[0])/d, (n1[1]+n2[1])/d) if d > 1e-6 else n1
                    inset.append((p1[0]+off[0]*bevel, p1[1]+off[1]*bevel))
                fronts.append(inset)
                for i in range(n):
                    a, b = ring[i], ring[(i+1) % n]
                    ex, ey = b[0]-a[0], b[1]-a[1]
                    l = math.hypot(ex, ey) or 1
                    on = (ey/l, -ex/l, 0.0)                  # outward normal (right of travel)
                    # side quad
                    A = (a[0], a[1], z0+bevel); B = (b[0], b[1], z0+bevel)
                    C = (b[0], b[1], z0+depth); D = (a[0], a[1], z0+depth)
                    if cam.faces_cam(on, A) or cam.faces_cam(on, C):
                        if cam.faces_cam(on, mul(add(A, C), 0.5)):
                            t = self.shade(on)*side_boost
                            c0, c1 = mat(t), mat(t*side_fade)
                            pa = mul(add(A, B), 0.5); pb = mul(add(C, D), 0.5)
                            g = ('lin', cam.proj(pa), cam.proj(pb), [(0, c0), (1, c1)])
                            self.poly3([[A, B, C, D]], g, layer=layer_side, subj=subj, stroke_self=True)
                    # bevel facet
                    if bevel > 0:
                        ia, ib = inset[i], inset[(i+1) % n]
                        bn = norm((on[0], on[1], -1.0))
                        if cam.faces_cam(bn, (a[0], a[1], z0)):
                            t = self.shade(bn, lo=0.14, k=1.0)
                            self.poly3([[A, B, (ib[0], ib[1], z0), (ia[0], ia[1], z0)]], mat(t),
                                       layer=layer_front-1, depth=0, subj=subj, stroke_self=True)
            f3 = [[(x, y, z0) for x, y in r] for r in fronts]
            fill = front if front is not None else mat(self.shade((0, 0, -1)))
            self.poly3(f3, fill, layer=layer_front, depth=0, subj=subj, evenodd=True, **({'edge': edge} if edge else {}))
            if grid:
                # perspective-correct pixel grid on the front face
                inner = Polygon(fronts[0], fronts[1:]).buffer(0)
                minx, miny, maxx, maxy = inner.bounds
                lines = []
                gx, gy = (grid, grid) if not isinstance(grid, tuple) else grid
                if gx:
                    x = math.floor(minx/gx)*gx + (gx/2 if isinstance(grid, tuple) else 0)
                    while x <= maxx:
                        lines.append(LineString([(x, miny-1), (x, maxy+1)])); x += gx
                if gy:
                    y = math.floor(miny/gy)*gy
                    while y <= maxy:
                        lines.append(LineString([(minx-1, y), (maxx+1, y)])); y += gy
                segs = []
                for ln in lines:
                    ix = ln.intersection(inner)
                    for s in (getattr(ix, 'geoms', None) or [ix]):
                        if s.is_empty or s.geom_type != 'LineString': continue
                        segs.append([self.cam.proj((px, py, z0)) for px, py in s.coords])
                self.items.append((layer_front, 1e9, dict(lines=segs, color=grid_color, opacity=grid_opacity,
                                                          width=grid_width)))

    def box3(self, x0, x1, y0, y1, z0, z1, mat, layer=0, subj=True, front=None, top=None, fade=0.6):
        P = lambda x, y, z: (x, y, z)
        faces = [
            ((0, 0, -1), [P(x0, y0, z0), P(x1, y0, z0), P(x1, y1, z0), P(x0, y1, z0)]),
            ((0, 0, 1), [P(x0, y0, z1), P(x1, y0, z1), P(x1, y1, z1), P(x0, y1, z1)]),
            ((0, 1, 0), [P(x0, y1, z0), P(x1, y1, z0), P(x1, y1, z1), P(x0, y1, z1)]),
            ((0, -1, 0), [P(x0, y0, z0), P(x1, y0, z0), P(x1, y0, z1), P(x0, y0, z1)]),
            ((-1, 0, 0), [P(x0, y0, z0), P(x0, y1, z0), P(x0, y1, z1), P(x0, y0, z1)]),
            ((1, 0, 0), [P(x1, y0, z0), P(x1, y1, z0), P(x1, y1, z1), P(x1, y0, z1)]),
        ]
        for n, q in faces:
            c = mul(add(q[0], q[2]), 0.5)
            if not self.cam.faces_cam(n, c): continue
            t = self.shade(n)
            if n == (0, 0, -1) and front is not None: fill = front
            elif n == (0, 1, 0) and top is not None: fill = top
            elif n[2] == 0:
                # fade into depth
                fa = mul(add(q[0], q[1]), 0.5) if n[1] != 0 else mul(add(q[0], q[1]), 0.5)
                pa = self.cam.proj(mul(add(q[0], q[1]), 0.5)); pb = self.cam.proj(mul(add(q[2], q[3]), 0.5))
                fill = ('lin', pa, pb, [(0, mat(t)), (1, mat(t*fade))])
            else:
                fill = mat(t)
            self.poly3([q], fill, layer=layer, depth=self.cam.depth(c), subj=subj, stroke_self=True)

    # ---- fitting + output
    def fit(self, rect, align='center', pts=None):
        pts = pts or self.subject
        xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
        bw, bh = max(xs)-min(xs), max(ys)-min(ys)
        x, y, w, h = rect
        k = min(w/bw, h/bh)
        ox = x + (w - bw*k)/2 - min(xs)*k
        if align == 'bottom': oy = y + h - bh*k - min(ys)*k
        elif align == 'top': oy = y - min(ys)*k
        else: oy = y + (h - bh*k)/2 - min(ys)*k
        self.T = (k, ox, oy)
        return self.T

    def tp(self, p):
        k, ox, oy = self.T
        return (p[0]*k+ox, p[1]*k+oy)

    def P(self, world):
        """world point -> final svg coords"""
        return self.tp(self.cam.proj(world))

    def render(self, ids, defs, min_layer=-1e9, max_layer=1e9):
        out = []
        items = sorted([it for it in self.items if min_layer <= it[0] <= max_layer], key=lambda it: (it[0], it[1]))
        for layer, _, d in items:
            if 'lines' in d:
                parts = []
                for s in d['lines']:
                    q = [self.tp(p) for p in s]
                    parts.append('M' + ' L'.join('%.2f,%.2f' % p for p in q))
                if parts:
                    out.append('<path d="%s" fill="none" stroke="%s" stroke-opacity="%.3f" stroke-width="%.2f"/>' %
                               (' '.join(parts), d['color'], d['opacity'],
                                d['width'] if d.get('width') else max(0.35, self.T[0]*0.0045)))
                continue
            rings = [[self.tp(p) for p in r] for r in d['rings']]
            dd = ' '.join('M' + ' L'.join('%.2f,%.2f' % p for p in r) + 'Z' for r in rings)
            fill = paint(d['fill'], ids, defs, self.tp)
            a = ''
            if d.get('evenodd'): a += ' fill-rule="evenodd"'
            if d.get('stroke_self'): a += ' stroke="%s" stroke-width="0.6" stroke-linejoin="round"' % fill
            if d.get('opacity') is not None: a += ' opacity="%.3f"' % d['opacity']
            out.append('<path d="%s" fill="%s"%s/>' % (dd, fill, a))
            if d.get('edge'):
                out.append('<path d="%s" fill="none" stroke="%s" stroke-width="%.2f" stroke-opacity="%.2f" stroke-linejoin="round"/>' %
                           (dd, d['edge'][0], d['edge'][1], d['edge'][2]))
        return '\n'.join(out)


class Ids:
    def __init__(self, prefix): self.prefix, self.n = prefix, 0
    def __call__(self, tag='g'):
        self.n += 1; return '%s-%s%d' % (self.prefix, tag, self.n)


def stops_xml(stops):
    s = []
    for st in stops:
        o = st[0]; c = st[1]; op = st[2] if len(st) > 2 else 1
        s.append('<stop offset="%.3f" stop-color="%s"%s/>' % (o, c, '' if op == 1 else ' stop-opacity="%.3f"' % op))
    return ''.join(s)


def paint(fill, ids, defs, tp=lambda p: p):
    if isinstance(fill, str): return fill
    kind = fill[0]
    gid = ids('g')
    if kind == 'lin':
        p1, p2 = tp(fill[1]), tp(fill[2])
        defs.append('<linearGradient id="%s" gradientUnits="userSpaceOnUse" x1="%.2f" y1="%.2f" x2="%.2f" y2="%.2f">%s</linearGradient>'
                    % (gid, p1[0], p1[1], p2[0], p2[1], stops_xml(fill[3])))
    elif kind == 'rad':
        c = tp(fill[1]); r = fill[2]
        defs.append('<radialGradient id="%s" gradientUnits="userSpaceOnUse" cx="%.2f" cy="%.2f" r="%.2f">%s</radialGradient>'
                    % (gid, c[0], c[1], r, stops_xml(fill[3])))
    return 'url(#%s)' % gid


def pathd(rings):
    return ' '.join('M' + ' L'.join('%.2f,%.2f' % tuple(p) for p in r) + 'Z' for r in rings)


def poly2d(polylist, fn=lambda p: p):
    rings = []
    for p in polylist:
        rings.append([fn(c) for c in list(p.exterior.coords)[:-1]])
        for i in p.interiors: rings.append([fn(c) for c in list(i.coords)[:-1]])
    return pathd(rings)
