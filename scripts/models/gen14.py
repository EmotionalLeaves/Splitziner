import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def wall_cabinet():
    """A wall cabinet: sides, top and bottom between them, a hanging rail under the top at the back, a thin back
    behind it, an adjustable shelf on pins, and two overlay doors. Hung on the wall by the rail."""
    W, D, H = 762.0, 304.8, 762.0
    p = [box(0, 0, 0, T, D, H), box(W - T, 0, 0, T, D, H)]
    p += [box(T, 0, 0, W - 2 * T, D - 6.35, T), box(T, 0, H - T, W - 2 * T, D - 6.35, T)]
    p += [box(T, D - 6.35 - T, H - T - 88.9, W - 2 * T, T, 88.9)]                 # hanging rail
    p += [box(T, D - 6.35, 0, W - 2 * T, 6.35, H)]                                 # back
    p += [box(T + 1.6, 25.4, 368.3, W - 2 * T - 3.2, D - 6.35 - T - 25.4, T)]       # shelf (on pins, clear of sides)
    p += [box(1.6, -T, 1.6, W / 2 - 3.2, T, H - 3.2), box(W / 2 + 1.6, -T, 1.6, W / 2 - 3.2, T, H - 3.2)]
    return p

write(os.path.join(out, "wall_cabinet.stl"), wall_cabinet())
print("wall_cabinet", len(wall_cabinet()))
