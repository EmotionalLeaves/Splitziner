import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def hull_tris(rings):
    """A convex solid from rectangles stacked along y (each 4 corners, same winding): returns its triangles."""
    tris = []
    first, last = rings[0], rings[-1]
    tris += [(first[0], first[2], first[1]), (first[0], first[3], first[2])]
    tris += [(last[0], last[1], last[2]), (last[0], last[2], last[3])]
    for a, b in zip(rings, rings[1:]):
        for i in range(4):
            j = (i + 1) % 4
            tris += [(a[i], a[j], b[j]), (a[i], b[j], b[i])]
    return tris

def raised_panel(x0, x1, z0, z1, yback, tongue=6.35, rise=9.525, bevel=38.1):
    """A raised panel: flat back at yback, a tongue `tongue` thick round its edge, bevelled up to a field `rise` higher."""
    r = lambda xa, xb, za, zb, y: [(xa, y, za), (xb, y, za), (xb, y, zb), (xa, y, zb)]
    return hull_tris([r(x0, x1, z0, z1, yback), r(x0, x1, z0, z1, yback - tongue), r(x0 + bevel, x1 - bevel, z0 + bevel, z1 - bevel, yback - tongue - rise)])

def raised_door(x0, y0, z0, w, h, sw=57.15, g=9.525):
    p = [box(x0, y0, z0, sw, T, h), box(x0 + w - sw, y0, z0, sw, T, h),
         box(x0 + sw, y0, z0, w - 2 * sw, T, sw), box(x0 + sw, y0, z0 + h - sw, w - 2 * sw, T, sw)]
    p += [raised_panel(x0 + sw - g, x0 + w - sw + g, z0 + sw - g, z0 + h - sw + g, y0 + (T + 6.35) / 2)]
    return p

def raised_cabinet():
    """A cabinet with two raised-panel doors (overlay)."""
    W, D, H = 762.0, 406.4, 762.0
    p = [box(0, 0, 0, T, D, H), box(W - T, 0, 0, T, D, H), box(T, 0, 0, W - 2 * T, D - 6.35, T), box(T, 0, H - T, W - 2 * T, D - 6.35, T)]
    p += [box(T, D - 6.35, 0, W - 2 * T, 6.35, H), box(T, 25.4, H / 2, W - 2 * T, D - 6.35 - 25.4, T)]
    dw = W / 2 - 3.2
    for x0 in (1.6, W / 2 + 1.6): p += raised_door(x0, -T, 1.6, dw, H - 3.2)
    return p

write(os.path.join(out, "raised_cabinet.stl"), raised_cabinet()); print("raised_cabinet", len(raised_cabinet()))
