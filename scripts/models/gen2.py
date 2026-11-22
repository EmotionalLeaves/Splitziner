import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def cyl(cx, cz, y0, y1, r, n=24, axis="y"):
    """Rod along Y (or X) centred at (cx, cz)."""
    ring = [(cx + r * math.cos(2 * math.pi * i / n), cz + r * math.sin(2 * math.pi * i / n)) for i in range(n)]
    tris = []
    for i in range(n):
        a, b = ring[i], ring[(i + 1) % n]
        A0, B0, A1, B1 = (a[0], y0, a[1]), (b[0], y0, b[1]), (a[0], y1, a[1]), (b[0], y1, b[1])
        tris += [(A0, B0, B1), (A0, B1, A1), ((cx, y0, cz), B0, A0), ((cx, y1, cz), A1, B1)]
    if axis == "x":
        tris = [tuple((v[1], v[0], v[2]) for v in t[::-1]) for t in tris]
    return tris

def wardrobe():
    W, D, H, kick = 1016.0, 584.2, 1828.8, 76.2
    p = [box(0, 0, 0, T, D, H), box(W - T, 0, 0, T, D, H)]
    p += [box(T, 0, H - T, W - 2 * T, D - 6.35, T), box(T, 0, kick, W - 2 * T, D - 6.35, T)]
    p += [box(T, 19.05, 0, W - 2 * T, T, kick)]
    p += [box(T, D - 6.35, kick, W - 2 * T, 6.35, H - kick)]
    sh = H - T - 304.8
    p += [box(T, 0, sh, W - 2 * T, D - 6.35, T)]                     # hat shelf
    dz = kick + T + 457.2
    p += [box(T, 0, dz, W - 2 * T, D - 6.35, T)]                     # drawer deck
    # hanging rod across, under the hat shelf, from side to side
    p += [cyl(D / 2, sh - 50.8, T, W - T, 15.9, axis="x")]
    # two drawers between bottom and deck, inset behind doors
    ih = 457.2
    for r in range(2):
        z0 = kick + T + r * ih / 2
        drawer(p, T + 12.7, z0 + 12.7, W - 2 * T - 25.4, ih / 2 - 38.1, 508.0, front_y=25.4,
               fx=T + 3.2, fz=z0 + 3.2, fw=W - 2 * T - 6.4, fh=ih / 2 - 6.4)
    # two overlay doors over the full height above the kick
    dw = W / 2 - 1.6
    p += [box(0, -T, kick + 1.6, dw, T, H - kick - 1.6), box(W / 2 + 1.6, -T, kick + 1.6, dw - 1.6, T, H - kick - 1.6)]
    return p

def oval_table():
    A, B, H, tt, leg = 1219.2, 609.6, 457.2, 25.4, 50.8
    n = 64
    poly = [(A / 2 + A / 2 * math.cos(2 * math.pi * i / n), B / 2 + B / 2 * math.sin(2 * math.pi * i / n)) for i in range(n)]
    p = [prism(poly, H - tt, tt)]
    for x in (200, A - 200 - leg):
        for y in (130, B - 130 - leg):
            p.append(box(x, y, 0, leg, leg, H - tt))
    a = 76.2
    p += [box(200 + leg, 130, H - tt - a, A - 400 - 2 * leg, T, a), box(200 + leg, B - 130 - T, H - tt - a, A - 400 - 2 * leg, T, a)]
    p += [box(200, 130 + leg, H - tt - a, T, B - 260 - 2 * leg, a), box(A - 200 - T, 130 + leg, H - tt - a, T, B - 260 - 2 * leg, a)]
    return p

def trestle():
    L, D, H, tt = 1828.8, 762.0, 742.95, 31.75
    p = [box(0, 0, H - tt, L, D, tt)]
    for x in (250.0, L - 250.0 - 76.2):
        p += [box(x, 76.2, 76.2, 76.2, 76.2, H - tt - 76.2 - 76.2) for _ in ()]
        p += [box(x - 0, D / 2 - 38.1, 76.2, 76.2, 76.2, H - tt - 152.4)]     # post
        p += [box(x - 0, 76.2, 0, 76.2, D - 152.4, 76.2)]                      # foot
        p += [box(x - 0, 101.6, H - tt - 76.2, 76.2, D - 203.2, 76.2)]         # cleat
    p += [box(250.0 + 76.2, D / 2 - 19.05, 304.8, L - 500.0 - 152.4, 38.1, 101.6)]  # stretcher between posts
    return p

for fn in (wardrobe, oval_table, trestle):
    write(os.path.join(out, fn.__name__ + ".stl"), fn())
    print(fn.__name__, len(fn()), "parts")
