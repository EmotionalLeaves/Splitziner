import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def picnic():
    """A picnic table: at each end two splayed legs (2x6) crossing under a top cleat and a seat support; top boards
    across the cleats, seat boards on the supports, and a centre brace under the top."""
    L, Wt = 1828.8, 762.0
    t2, w6, w4 = 38.1, 139.7, 88.9
    p = []
    Htop = 762.0; Hseat = 457.2
    ang = math.radians(22.0)        # legs lean in from the vertical, across the table
    leglen = (Htop - t2) / math.cos(ang)
    for xe in (152.4, L - 152.4 - t2):
        # top cleat across the table under the top boards, seat support lower and longer
        p.append(box(xe, 0, Htop - t2 - w4, t2, Wt, w4))                       # top cleat (2x4 on edge)
        p.append(box(xe, -381.0, Hseat - t2 - w6, t2, Wt + 762.0, w6))           # seat support (2x6 on edge)
        for side in (-1, 1):
            cy = Wt / 2 + side * (math.tan(ang) * (Htop - t2) / 2)
            cz = (Htop - t2) / 2
            # a leg is a 2x6 standing in the y-z plane, thickness along x, tilted about x
            # a 2x6 leaning in, both ends sawn level: a parallelogram in the y-z plane, thickness along x
            hw = w6 / math.cos(ang) / 2; ht = Htop - t2
            ytop = Wt / 2 + side * hw; ybot = ytop + side * math.tan(ang) * ht
            pts = [(ybot - hw, 0.0), (ybot + hw, 0.0), (ytop + hw, ht), (ytop - hw, ht)]
            a_ = [(xe - t2, y, z) for y, z in pts]; b_ = [(xe, y, z) for y, z in pts]
            tris = [(a_[0], a_[2], a_[1]), (a_[0], a_[3], a_[2]), (b_[0], b_[1], b_[2]), (b_[0], b_[2], b_[3])]
            for i in range(4):
                j = (i + 1) % 4
                tris += [(a_[i], a_[j], b_[j]), (a_[i], b_[j], b_[i])]
            p.append(tris)
    # top boards: 5 2x6 along the length
    n = 5; gap = (Wt - n * w6) / (n - 1)
    for i in range(n):
        p.append(box(0, i * (w6 + gap), Htop - t2, L, w6, t2))
    # seat boards: 2 each side
    for y0 in (-381.0, Wt + 381.0 - 2 * w6 - 6.35):
        for k in range(2):
            p.append(box(0, y0 + k * (w6 + 6.35), Hseat - t2, L, w6, t2))
    return p

write(os.path.join(out, "picnic2.stl"), picnic())
print("picnic2", len(picnic()))
