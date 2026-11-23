import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def platform_bed():
    """A queen platform bed: a box frame of 2x10s on six legs (corners and the middle of each side), a centre
    beam on two legs, slats across, and a headboard panel between tall head posts."""
    L, W = 2032.0, 1524.0 + 2 * 38.1
    t2, w10 = 38.1, 235.0
    leg = 88.9; zr = 152.4
    p = []
    # frame: side rails outside, end rails between
    p += [box(0, 0, zr, t2, L, w10), box(W - t2, 0, zr, t2, L, w10)]
    p += [box(t2, 0, zr, W - 2 * t2, t2, w10), box(t2, L - t2, zr, W - 2 * t2, t2, w10)]
    # legs inside the corners (short) and posts at the head (tall)
    Hh = 1219.2
    for x in (t2, W - t2 - leg):
        p.append(box(x, t2, 0, leg, leg, Hh))            # head posts, full height
        p.append(box(x, L - t2 - leg, 0, leg, leg, zr + w10))   # foot legs
        p.append(box(x, L / 2 - leg / 2, 0, leg, leg, zr + w10 - t2))   # middle legs under cleats? (under ledger)
    # centre beam with two legs
    lz = zr + w10 - 38.1 - 19.05
    top = lz + 38.1          # underside of the slats
    p.append(box(W / 2 - t2 / 2, t2, top - 140.0, t2, L - 2 * t2, 140.0))
    for y in (L / 3, 2 * L / 3):
        p.append(box(W / 2 - leg / 2, y, 0, leg, leg, top - 140.0))
    # ledgers inside the side rails, slats on them and the beam
    p += [box(t2 + leg, t2 + leg, lz, 38.1, L - 2 * t2 - 2 * leg, 38.1), box(W - t2 - leg - 38.1, t2 + leg, lz, 38.1, L - 2 * t2 - 2 * leg, 38.1)]
    n = 14; sw = 88.9; gap = (L - 2 * t2 - 2 * leg - n * sw) / (n - 1)
    for i in range(n):
        p.append(box(t2 + leg, t2 + leg + i * (sw + gap), lz + 38.1, W - 2 * t2 - 2 * leg, sw, 19.05))
    # headboard panel between the head posts, above the frame
    p.append(box(t2 + leg, t2 + (leg - 19.05) / 2, zr + w10 + 50.8, W - 2 * t2 - 2 * leg, 19.05, Hh - zr - w10 - 50.8 - 50.8))
    return p

write(os.path.join(out, "platform_bed.stl"), platform_bed())
print("platform_bed", len(platform_bed()))
