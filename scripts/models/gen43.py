import os, sys, math
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])

def sawhorse():
    """A sawhorse 36 in long and 28 in tall: a 2x4 beam on edge on top, four 2x4 legs against its sides, flush with its
    top, splayed out 12 degrees to the sides and 10 degrees to the ends."""
    L, H = 914.4, 711.2
    bw, bh = 38.1, 88.9
    p = [box(0, -bw / 2, H - bh, L, bw, bh)]
    a_side = math.radians(12); a_end = math.radians(10)
    lt, lw = 38.1, 88.9
    top = H
    for xe, sx in ((101.6, -1), (L - 101.6, 1)):
        for sy in (-1, 1):
            # leg from top (under beam, beside it) splaying out
            dx = sx * top * math.tan(a_end); dy = sy * top * math.tan(a_side)
            cx = xe; cy = sy * (bw / 2 + lt / 2)
            h = lw / 2; t = lt / 2
            v = [(cx + dx - h, cy + dy - t, 0), (cx + dx + h, cy + dy - t, 0), (cx + dx + h, cy + dy + t, 0), (cx + dx - h, cy + dy + t, 0),
                 (cx - h, cy - t, top), (cx + h, cy - t, top), (cx + h, cy + t, top), (cx - h, cy + t, top)]
            p.append(hexa(v))
    return p

write(os.path.join("stl", "sawhorse.stl"), sawhorse()); print("sawhorse")
