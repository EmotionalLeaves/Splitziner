import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def shaker(x0, y0, z0, w, h, sw=57.15, g=9.525, pt=6.35, t=T):
    """A frame-and-panel piece in the x-z plane, its front face at y0: two stiles, two rails, a panel in grooves."""
    return [box(x0, y0, z0, sw, t, h), box(x0 + w - sw, y0, z0, sw, t, h),
            box(x0 + sw, y0, z0, w - 2 * sw, t, sw), box(x0 + sw, y0, z0 + h - sw, w - 2 * sw, t, sw),
            box(x0 + sw - g, y0 + (t - pt) / 2, z0 + sw - g, w - 2 * sw + 2 * g, pt, h - 2 * sw + 2 * g)]

def shaker_base():
    """A kitchen base cabinet: drawer with a five-piece (Shaker) front over two Shaker overlay doors."""
    W, D, H, kick, kd = 762.0, 584.2, 876.3, 101.6, 76.2
    p = []
    p += [box(0, 0, 0, T, D, H), box(W - T, 0, 0, T, D, H)]
    p += [box(T, 0, kick, W - 2 * T, D - 6.35, T)]
    p += [box(T, kd, 0, W - 2 * T, T, kick)]
    p += [box(T, 0, H - T, W - 2 * T, 101.6, T), box(T, D - 6.35 - 101.6, H - T, W - 2 * T, 101.6, T)]
    p += [box(T, 0, H - 203.2, W - 2 * T, D - 6.35, T)]
    p += [box(T, 50.8, kick + T + 300, W - 2 * T, D - 6.35 - 50.8, T)]
    p += [box(T, D - 6.35, kick, W - 2 * T, 6.35, H - kick)]
    drawer(p, T + 12.7, H - 203.2 + T + 6.35, W - 2 * T - 25.4, 127.0, 508.0)
    p += shaker(1.6, -T, H - 203.2 + T / 2 + 1.6, W - 3.2, 203.2 - T / 2 - 3.2, sw=50.8)
    z0 = kick + T / 2; z1 = H - 203.2 + T / 2 - 1.6
    p += shaker(1.6, -T, z0, W / 2 - 3.2, z1 - z0) + shaker(W / 2 + 1.6, -T, z0, W / 2 - 3.2, z1 - z0)
    return p

def shaker_inset():
    """A wall cabinet with a face frame and two inset Shaker doors, 1/16 in gap round them, on a French-free back."""
    W, D, H = 762.0, 304.8, 762.0
    ffw = 38.1
    p = [box(0, 0, 0, T, D, H), box(W - T, 0, 0, T, D, H), box(T, 0, 0, W - 2 * T, D - 6.35, T), box(T, 0, H - T, W - 2 * T, D - 6.35, T)]
    p += [box(T, D - 6.35, T, W - 2 * T, 6.35, H - 2 * T), box(T, 25.4, H / 2, W - 2 * T, D - 6.35 - 25.4, T)]
    # face frame: stiles full height, rails between
    p += [box(0, -T, 0, ffw, T, H), box(W - ffw, -T, 0, ffw, T, H), box(ffw, -T, 0, W - 2 * ffw, T, ffw), box(ffw, -T, H - ffw, W - 2 * ffw, T, ffw)]
    ow = W - 2 * ffw; oh = H - 2 * ffw; gap = 1.6
    dw = (ow - 3 * gap) / 2
    for x0 in (ffw + gap, ffw + 2 * gap + dw):
        p += shaker(x0, -T, ffw + gap, dw, oh - 2 * gap)
    return p

for fn in (shaker_base, shaker_inset):
    write(os.path.join(out, fn.__name__ + ".stl"), fn()); print(fn.__name__, len(fn()))
