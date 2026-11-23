import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def crib():
    """A crib: four corner posts, each side and end a frame of top and bottom rails with upright slats between them;
    a mattress platform of slats on cleats inside the bottom rails of the long sides."""
    L, Wd, H, post = 1384.3, 755.65, 914.4, 50.8
    p = []
    for x in (0, Wd - post):
        for y in (0, L - post):
            p.append(box(x, y, 0, post, post, H))
    rt, rh = 19.05, 63.5        # rails 3/4 x 2-1/2
    zb, zt = 254.0, H - 25.4 - rh
    sw, gap = 44.45, 57.15      # slats 1-3/4 wide, 2-1/4 in gaps
    def side(x0, y0, y1, along_y=True):
        q = []
        span = (y1 - y0)
        q += [box(x0, y0, zb, rt, span, rh), box(x0, y0, zt, rt, span, rh)] if along_y else [box(y0, x0, zb, span, rt, rh), box(y0, x0, zt, span, rt, rh)]
        n = int((span - gap) // (sw + gap))
        used = n * sw + (n + 1) * gap
        start = y0 + (span - used) / 2 + gap
        for i in range(n):
            s = start + i * (sw + gap)
            q.append(box(x0, s, zb + rh, rt, sw, zt - zb - rh) if along_y else box(s, x0, zb + rh, sw, rt, zt - zb - rh))
        return q
    p += side((post - rt) / 2, post, L - post)
    p += side(Wd - post + (post - rt) / 2, post, L - post)
    p += side((post - rt) / 2, post, Wd - post, along_y=False)
    p += side(L - post + (post - rt) / 2, post, Wd - post, along_y=False)
    return p

write(os.path.join(out, "crib.stl"), crib())
print("crib", len(crib()))
