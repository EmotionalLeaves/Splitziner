import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def rbox(c, axes, half):
    """A box centred at c with unit axes (3 vectors) and half sizes."""
    pts = []
    for sx in (-1, 1):
        for sy in (-1, 1):
            for sz in (-1, 1):
                pts.append(tuple(c[i] + sx * half[0] * axes[0][i] + sy * half[1] * axes[1][i] + sz * half[2] * axes[2][i] for i in range(3)))
    # corner order to match box(): (x,y,z) bits
    def P(sx, sy, sz): return pts[(0 if sx < 0 else 4) + (0 if sy < 0 else 2) + (0 if sz < 0 else 1)]
    a = [P(-1,-1,-1), P(1,-1,-1), P(1,1,-1), P(-1,1,-1), P(-1,-1,1), P(1,-1,1), P(1,1,1), P(-1,1,1)]
    f = [(0, 2, 1), (0, 3, 2), (4, 5, 6), (4, 6, 7), (0, 1, 5), (0, 5, 4), (1, 2, 6), (1, 6, 5), (2, 3, 7), (2, 7, 6), (3, 0, 4), (3, 4, 7)]
    return [(a[i], a[j], a[k]) for i, j, k in f]

def norm(v):
    l = math.sqrt(sum(x * x for x in v)); return tuple(x / l for x in v)
def cross(a, b): return (a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])

def stick(p0, p1, w, t, side=(1, 0, 0)):
    """A stick from p0 to p1 (centre line), w wide along `side` (made square to it), t thick."""
    ax = norm(tuple(b - a for a, b in zip(p0, p1)))
    n = norm(cross(ax, side)); s = cross(n, ax)
    L = math.dist(p0, p1)
    c = tuple((a + b) / 2 for a, b in zip(p0, p1))
    return rbox(c, (s, n, ax), (w / 2, t / 2, L / 2))

def leaner(b0, b1, z0, z1, w, t):
    """A board w wide (across, near x) and t thick, its centre line from (b0, z0) to (b1, z1), its ends cut level."""
    c0 = (b0[0], b0[1], z0); c1 = (b1[0], b1[1], z1)
    ax = norm(tuple(q - p for p, q in zip(c0, c1)))
    sx = (1.0, 0.0, 0.0); d = sum(a * b for a, b in zip(sx, ax)); s = norm(tuple(a - d * b for a, b in zip(sx, ax)))
    n = cross(ax, s)
    def at(o, z):
        p = tuple(c0[i] + o[i] for i in range(3)); lam = (z - p[2]) / ax[2]
        return tuple(p[i] + lam * ax[i] for i in range(3))
    offs = [tuple(-w / 2 * s[i] - t / 2 * n[i] for i in range(3)), tuple(w / 2 * s[i] - t / 2 * n[i] for i in range(3)),
            tuple(w / 2 * s[i] + t / 2 * n[i] for i in range(3)), tuple(-w / 2 * s[i] + t / 2 * n[i] for i in range(3))]
    a = [at(o, z0) for o in offs] + [at(o, z1) for o in offs]
    f = [(0, 2, 1), (0, 3, 2), (4, 5, 6), (4, 6, 7), (0, 1, 5), (0, 5, 4), (1, 2, 6), (1, 6, 5), (2, 3, 7), (2, 7, 6), (3, 0, 4), (3, 4, 7)]
    tris = [(a[i], a[j], a[k]) for i, j, k in f]
    # keep outward winding whichever way the axis runs
    vol = sum((p[0] * (q[1] * r[2] - q[2] * r[1]) - p[1] * (q[0] * r[2] - q[2] * r[0]) + p[2] * (q[0] * r[1] - q[1] * r[0])) for p, q, r in tris)
    return tris if vol > 0 else [(p, r, q) for p, q, r in tris]

def front_face_y(leg, x, z):
    """y of the leg's front face (its smallest-y long face) at (x, z)."""
    best = None
    for tri in leg:
        p, q, r = tri
        u = tuple(q[i] - p[i] for i in range(3)); v = tuple(r[i] - p[i] for i in range(3)); nn = cross(u, v)
        if nn[1] > -0.7 * math.sqrt(sum(c * c for c in nn)): continue          # front faces look toward -y
        y = p[1] - (nn[0] * (x - p[0]) + nn[2] * (z - p[2])) / nn[1]
        best = y if best is None else min(best, y)
    return best

def easel():
    """A studio A-frame easel: two front legs leaning in and back, joined by a top block, a back leg hinged to the
    block, a canvas ledge and a cross brace screwed to the front of the legs. Leg ends cut level."""
    H = 1828.8; spread = 660.4; lean = 381.0; w = 44.45; t = 19.05
    p = []
    xt = 70.0; Hl = H - 50.8
    L = leaner((-spread / 2, 0.0), (-xt, lean), 0.0, Hl, w, t); R = leaner((spread / 2, 0.0), (xt, lean), 0.0, Hl, w, t)
    p += [L, R]
    ytop = max(v[1] for tri in L for v in tri if v[2] > Hl - 0.01)
    yfront = min(v[1] for tri in L for v in tri if v[2] > Hl - 0.01)
    p.append(box(-xt - w / 2, yfront, Hl, 2 * xt + w, ytop - yfront, 50.8))
    # back leg: its top front corner against the back of the block, hinged there
    p.append(leaner((0.0, ytop + 762.0), (0.0, ytop + t / 2 + 4.0), 0.0, H - 12.7, w, t))
    B = p[-1]
    dy = ytop - min(v[1] for tri in B for v in tri if v[2] > H - 13)
    p[-1] = [tuple((v[0], v[1] + dy, v[2]) for v in tri) for tri in B]
    def half_x(z): return spread / 2 - (spread / 2 - xt) * z / Hl + w / 2
    for zl, hh, extra in ((762.0, 88.9, 25.0), (1371.6, 63.5, 0.0)):
        xc = (spread / 2 - (spread / 2 - xt) * zl / Hl)
        yb = front_face_y(R, xc, zl) + 0.3              # touching the legs' faces along its bottom edge
        p.append(box(-half_x(zl + hh) - extra, yb - T, zl, 2 * half_x(zl + hh) + 2 * extra, T, hh))
    return p

write(os.path.join(out, "easel.stl"), easel()); print("easel", len(easel()))
