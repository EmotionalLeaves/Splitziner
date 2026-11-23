import os, sys, math
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
T = 19.05

def vcyl(cx, cy, z0, r, h, n=20):
    pts = [(cx + r * math.cos(2 * math.pi * i / n), cy + r * math.sin(2 * math.pi * i / n)) for i in range(n)]
    return prism(pts, z0, h)

def drying_rack():
    """A canvas drying rack: a base, two end panels, top rails front and back, and pairs of 1/2 in dowels
    standing from the base to the rails, making 12 slots a canvas stands in on its edge."""
    W, D, H = 914.4, 457.2, 762.0
    p = [box(0, 0, 0, W, D, T), box(0, 0, T, T, D, H - T), box(W - T, 0, T, T, D, H - T)]
    rail = 63.5
    p += [box(T, 0, H - T, W - 2 * T, rail, T), box(T, D - rail, H - T, W - 2 * T, rail, T)]
    n = 12; pitch = (W - 2 * T) / n
    for i in range(1, n):
        x = T + i * pitch
        for y in (rail / 2, D - rail / 2):
            p.append(vcyl(x, y, T, 6.35, H - 2 * T))
    return p

write(os.path.join("stl", "drying_rack.stl"), drying_rack()); print("drying_rack")
