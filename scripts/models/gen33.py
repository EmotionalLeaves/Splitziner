import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def cyl(cx, cy, z0, r, h, n=24):
    """A vertical cylinder as a closed triangle list in gen.py's part format (list of triangles)."""
    tris = []
    pts = [(cx + r * math.cos(2 * math.pi * i / n), cy + r * math.sin(2 * math.pi * i / n)) for i in range(n)]
    for i in range(n):
        a = pts[i]; b = pts[(i + 1) % n]
        tris.append(((cx, cy, z0), (b[0], b[1], z0), (a[0], a[1], z0)))
        tris.append(((cx, cy, z0 + h), (a[0], a[1], z0 + h), (b[0], b[1], z0 + h)))
        tris.append(((a[0], a[1], z0), (b[0], b[1], z0), (b[0], b[1], z0 + h)))
        tris.append(((a[0], a[1], z0), (b[0], b[1], z0 + h), (a[0], a[1], z0 + h)))
    return tris

def taboret():
    """An artist's taboret: a carcass on four casters, three drawers over an open shelf, a top with a gallery rail on three sides."""
    W, D = 508.0, 457.2; wheel = 76.2; H = 762.0
    z0 = wheel
    p = [box(0, 0, z0, T, D, H - z0 - T), box(W - T, 0, z0, T, D, H - z0 - T)]
    p += [box(T, 0, z0, W - 2 * T, D - 6.35, T), box(T, D - 6.35, z0, W - 2 * T, 6.35, H - z0 - T)]
    shelf_z = z0 + 200.0
    p += [box(T, 0, shelf_z, W - 2 * T, D - 6.35, T)]
    p += [box(0, 0, H - T, W, D, T)]
    # Gallery: back and two side rails standing on the top.
    g = 50.8
    p += [box(0, D - T, H, W, T, g), box(0, 0, H, T, D - T, g), box(W - T, 0, H, T, D - T, g)]
    n = 3; top = H - T; bottom = shelf_z + T; slot = (top - bottom) / n
    for i in range(n):
        z = bottom + i * slot
        drawer(p, T + 12.7, z + 6.35, W - 2 * T - 25.4, slot - 19.05, D - 6.35 - 25.4,
               fx=T + 1.6, fz=z + 1.6, fw=W - 2 * T - 3.2, fh=slot - 3.2)
    for (x, y) in [(50.8, 50.8), (W - 50.8, 50.8), (50.8, D - 50.8), (W - 50.8, D - 50.8)]:
        p.append(cyl(x, y, 0.0, 35.0, wheel))
    return p

write(os.path.join(out, "taboret.stl"), taboret()); print("taboret", len(taboret()))
