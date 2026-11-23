import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def chair():
    """A dining chair: square legs, aprons under the seat, low side stretchers, a seat board stopping at the back
    legs, and a back of three splats between a lower back rail and a top rail."""
    W, D, Hs, Hb, leg = 457.2, 457.2, 457.2, 914.4, 38.1
    p = []
    for x in (0, W - leg):
        p.append(box(x, 0, 0, leg, leg, Hs - 19.05))         # front legs to under the seat
        p.append(box(x, D - leg, 0, leg, leg, Hb))           # back legs full height
    az, ah = Hs - 19.05 - 76.2, 76.2
    p += [box(leg, (leg - 19.05) / 2, az, W - 2 * leg, 19.05, ah)]                 # front apron
    p += [box(leg, D - leg + (leg - 19.05) / 2, az, W - 2 * leg, 19.05, ah)]       # back apron
    for x in (0, W - leg):
        p += [box(x + (leg - 19.05) / 2, leg, az, 19.05, D - 2 * leg, ah)]         # side aprons
        p += [box(x + (leg - 19.05) / 2, leg, 152.4, 19.05, D - 2 * leg, 38.1)]    # side stretchers
    p += [box(0, 0, Hs - 19.05, W, D - leg, 19.05)]                                # seat
    # back: lower back rail, top rail, three splats between
    rl, rt = Hs + 76.2, Hb - 88.9
    p += [box(leg, D - leg + (leg - 19.05) / 2, rl, W - 2 * leg, 19.05, 50.8)]
    p += [box(leg, D - leg + (leg - 19.05) / 2, rt, W - 2 * leg, 19.05, 76.2)]
    sw = 50.8; n = 3; span = W - 2 * leg; g = (span - n * sw) / (n + 1)
    for i in range(n):
        p += [box(leg + g + i * (sw + g), D - leg + (leg - 12.7) / 2, rl + 50.8, sw, 12.7, rt - rl - 50.8)]
    return p

write(os.path.join(out, "chair.stl"), chair())
print("chair", len(chair()))
