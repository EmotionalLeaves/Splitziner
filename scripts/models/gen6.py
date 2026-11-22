import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
exec(open("gen3.py").read().split("def mitred_box")[0].split('out = "stl"')[1])
out = "stl"

def tilted(x0, yp, zp, w, y_lo, y_hi, z_lo, z_hi, deg):
    """A box x0..x0+w, with local y_lo..y_hi and z_lo..z_hi measured from the pivot line (yp, zp), leaned back by deg."""
    a = math.radians(deg); c, s = math.cos(a), math.sin(a)
    def r(px, ly, lz):
        return (px, yp + ly * c + lz * s, zp - ly * s + lz * c)
    v = [r(*q) for q in [(x0, y_lo, z_lo), (x0 + w, y_lo, z_lo), (x0 + w, y_hi, z_lo), (x0, y_hi, z_lo),
                          (x0, y_lo, z_hi), (x0 + w, y_lo, z_hi), (x0 + w, y_hi, z_hi), (x0, y_hi, z_hi)]]
    return hexa(v)

def garden_bench():
    L, D, SH = 1219.2, 457.2, 431.8
    leg, t = 63.5, 25.4
    p = []
    for x in (0.0, L - leg):
        p += [box(x, 0, 0, leg, leg, SH - t), box(x, D - leg, 0, leg, leg, SH - t)]        # legs
        p += [box(x, leg, SH - t - 88.9, leg, D - 2 * leg, 88.9)]                         # side rail under seat
    p += [box(leg, 0, SH - t - 88.9, L - 2 * leg, t, 88.9), box(leg, D - t, SH - t - 88.9, L - 2 * leg, t, 88.9)]  # aprons
    n = 5; sw = (D - 4 * 6.35) / n
    for i in range(n):
        p.append(box(0, i * (sw + 6.35), SH - t, L, sw, t))                               # seat slats
    for x in (0.0, L - leg):
        p.append(tilted(x, D, SH, leg, -t, 0.0, 0.0, 457.2, 15))                            # back supports, pivot at back top of seat
    for i in range(3):
        p.append(tilted(0.0, D, SH, L, -2 * t, -t, 101.6 + i * 127.0, 190.5 + i * 127.0, 15))  # back slats in front of supports
    return p

write(os.path.join(out, "garden_bench.stl"), garden_bench())
print("garden_bench", len(garden_bench()))
