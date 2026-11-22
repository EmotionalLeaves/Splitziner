import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def console():
    """A low media console with two sliding doors in tracks, one running in front of the other."""
    W, D, H = 1524.0, 457.2, 609.6
    p = [box(0, 0, 0, T, D, H), box(W - T, 0, 0, T, D, H)]
    p += [box(T, 0, H - T, W - 2 * T, D, T), box(T, 0, 76.2, W - 2 * T, D, T)]
    p += [box(T, 50.8, 0, W - 2 * T, T, 76.2)]
    p += [box(T, D - 6.35, 76.2 + T, W - 2 * T, 6.35, H - 76.2 - 2 * T)]
    p += [box(W / 2 - T / 2, 2 * T + 3.2, 76.2 + T, T, D - 6.35 - 2 * T - 3.2, H - 76.2 - 2 * T)]   # centre divider behind doors
    dh = H - 76.2 - 2 * T - 3.2
    dw = (W - 2 * T) / 2 + 12.7
    p += [box(T, 0, 76.2 + T + 1.6, dw, T, dh)]                       # front door, left
    p += [box(W - T - dw, T + 1.6, 76.2 + T + 1.6, dw, T, dh)]        # back door, right, behind
    return p

write(os.path.join(out, "console.stl"), console())
print("console", len(console()))
