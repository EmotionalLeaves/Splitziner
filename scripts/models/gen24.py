import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
exec(open("gen23.py").read().split("def raised_panel")[0].split('out = "stl"')[1])
out = "stl"

def tapered_leg(x0, y0, s, h, bottom, start, inx, iny):
    """A square leg s wide, h tall, tapered on its two inside faces (towards +x if inx>0 else -x, likewise y) from
    `start` below the top down to `bottom` square at the foot."""
    def ring(z, w):
        xa, xb = (x0, x0 + w) if inx < 0 else (x0 + s - w, x0 + s)
        # the outside faces stay put; inside faces move in
        xa, xb = (x0, x0 + w) if inx > 0 else (x0 + s - w, x0 + s)
        ya, yb = (y0, y0 + w) if iny > 0 else (y0 + s - w, y0 + s)
        return [(xa, ya, z), (xb, ya, z), (xb, yb, z), (xa, yb, z)]
    # rings stacked along z: use the y-stack helper by swapping axes
    rs = [ring(0.0, bottom), ring(h - start, s), ring(h, s)]
    tris = []
    first, last = rs[0], rs[-1]
    tris += [(first[0], first[2], first[1]), (first[0], first[3], first[2])]
    tris += [(last[0], last[1], last[2]), (last[0], last[2], last[3])]
    for a, b in zip(rs, rs[1:]):
        for i in range(4):
            j = (i + 1) % 4
            tris += [(a[i], a[j], b[j]), (a[i], b[j], b[i])]
    return tris

def taper_table():
    """An end table: four legs tapered on their inside faces, aprons, a top overhanging 1 in."""
    W, D, H = 508.0, 508.0, 609.6
    s = 38.1; ap = 101.6; inset = 25.4
    p = []
    for (x, y, inx, iny) in ((inset, inset, 1, 1), (W - inset - s, inset, -1, 1), (inset, D - inset - s, 1, -1), (W - inset - s, D - inset - s, -1, -1)):
        p.append(tapered_leg(x, y, s, H - T, 19.05, ap + 25.4, inx, iny))
    zt = H - T
    p += [box(inset + s, inset + 6.35, zt - ap, W - 2 * inset - 2 * s, T, ap), box(inset + s, D - inset - 6.35 - T, zt - ap, W - 2 * inset - 2 * s, T, ap)]
    p += [box(inset + 6.35, inset + s, zt - ap, T, D - 2 * inset - 2 * s, ap), box(W - inset - 6.35 - T, inset + s, zt - ap, T, D - 2 * inset - 2 * s, ap)]
    p += [box(0, 0, zt, W, D, T)]
    return p

write(os.path.join(out, "taper_table.stl"), taper_table()); print("taper_table", len(taper_table()))
