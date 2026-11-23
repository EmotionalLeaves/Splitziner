import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def cleat_cabinet():
    """A small wall cabinet hung on a French cleat: a box (sides, top, bottom, back) with a 45-degree bevelled strip
    across the top of its back, hooking over a matching strip screwed to the wall."""
    W, D, H, t = 610.0, 254.0, 457.2, 19.05
    p = [box(0, 0, 0, t, D, H), box(W - t, 0, 0, t, D, H), box(t, 0, 0, W - 2 * t, D, t), box(t, 0, H - t, W - 2 * t, D, t)]
    p += [box(t, D - 6.35 - t - 19.05 - 19.05 + 19.05, t, W - 2 * t, 6.35, H - 2 * t)]   # thin back, set in for the cleat
    # cabinet cleat: 3/4 thick, 3 in tall, bevelled 45 deg at the top, back face toward the wall (y = D), behind the back panel
    y0 = D - t; ch = 76.2
    def bev(x0, x1, ybk, yfr, z0, z1, top_back_lower):
        # profile in y-z: a strip from yfr (front) to ybk (back), z0..z1, the top cut at 45 deg
        if top_back_lower: pts = [(yfr, z0), (ybk, z0), (ybk, z1 - (ybk - yfr)), (yfr, z1)]
        else: pts = [(yfr, z0), (ybk, z0 + (ybk - yfr)), (ybk, z1), (yfr, z1)]
        a = [(x0, y, z) for y, z in pts]; b = [(x1, y, z) for y, z in pts]
        tris = [(a[0], a[2], a[1]), (a[0], a[3], a[2]), (b[0], b[1], b[2]), (b[0], b[2], b[3])]
        for i in range(4):
            j = (i + 1) % 4
            tris += [(a[i], a[j], b[j]), (a[i], b[j], b[i])]
        return tris
    p.append(bev(t, W - t, D, y0, H - t - ch, H - t, True))
    # wall cleat: behind the cabinet (y from D to D + t), its top bevel sloping the other way, under the cabinet cleat
    p.append(bev(t, W - t, D + t, D, H - t - ch - 50.8, H - t - t, False))
    return p

write(os.path.join(out, "cleat_cabinet.stl"), cleat_cabinet())
print("cleat_cabinet", len(cleat_cabinet()))
