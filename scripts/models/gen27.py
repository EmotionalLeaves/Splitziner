import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def planter():
    """A raised cedar planter on legs: 2x2 corner legs, 1x6 side boards stacked two high screwed to the legs, a
    slatted bottom on cleats with gaps for drainage, a cap rail round the top."""
    W, D = 914.4, 457.2; leg = 38.1; t = 19.05; bw = 139.7; H = 762.0
    p = []
    zb = H - 2 * bw - 19.05      # bottom of the box
    for x, y in ((0, 0), (W - leg, 0), (0, D - leg), (W - leg, D - leg)):
        p.append(box(x, y, 0, leg, leg, H - 19.05))
    for k in range(2):
        z = zb + k * bw
        p += [box(0, -t, z, W, t, bw), box(0, D, z, W, t, bw), box(-t, 0, z, t, D, bw), box(W, 0, z, t, D, bw)]
    # cleats along the long sides inside, slats across
    p += [box(leg, 0, zb, W - 2 * leg, 38.1, 38.1), box(leg, D - 38.1, zb, W - 2 * leg, 38.1, 38.1)]
    n = 5; sw = 139.7; gap = (W - 2 * leg - n * sw) / (n - 1)
    for i in range(n):
        p.append(box(leg + i * (sw + gap), 0, zb + 38.1, sw, D, t))
    # cap rail: mitred would be nicer; butt here
    p += [box(-t, -t, H - 19.05, W + 2 * t, 63.5, 19.05), box(-t, D + t - 63.5, H - 19.05, W + 2 * t, 63.5, 19.05)]
    p += [box(-t, -t + 63.5, H - 19.05, 63.5, D + 2 * t - 127, 19.05), box(W + t - 63.5, -t + 63.5, H - 19.05, 63.5, D + 2 * t - 127, 19.05)]
    return p

write(os.path.join(out, "planter.stl"), planter()); print("planter", len(planter()))
