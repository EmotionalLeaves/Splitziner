import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def shaker_cabinet():
    """A cabinet with two frame-and-panel (Shaker) doors: each door is two stiles and two rails round a 1/4 in panel
    that sits in grooves (drawn as overlapping the frame by 3/8 in, the groove depth)."""
    W, D, H = 762.0, 406.4, 762.0
    p = [box(0, 0, 0, T, D, H), box(W - T, 0, 0, T, D, H), box(T, 0, 0, W - 2 * T, D - 6.35, T), box(T, 0, H - T, W - 2 * T, D - 6.35, T)]
    p += [box(T, D - 6.35, 0, W - 2 * T, 6.35, H)]
    p += [box(T, 25.4, H / 2, W - 2 * T, D - 6.35 - 25.4, T)]
    dw = W / 2 - 3.2; dh = H - 3.2; sw = 57.15; g = 9.525
    for x0 in (1.6, W / 2 + 1.6):
        y0 = -T; z0 = 1.6
        p += [box(x0, y0, z0, sw, T, dh), box(x0 + dw - sw, y0, z0, sw, T, dh)]                 # stiles
        p += [box(x0 + sw, y0, z0, dw - 2 * sw, T, sw), box(x0 + sw, y0, z0 + dh - sw, dw - 2 * sw, T, sw)]   # rails
        # panel in the grooves: centred in the thickness, 1/4 in thick, reaching 3/8 in into the frame
        p += [box(x0 + sw - g, y0 + (T - 6.35) / 2, z0 + sw - g, dw - 2 * sw + 2 * g, 6.35, dh - 2 * sw + 2 * g)]
    return p

write(os.path.join(out, "shaker_cabinet.stl"), shaker_cabinet())
print("shaker_cabinet", len(shaker_cabinet()))

def panel_chest():
    """A blanket chest with frame-and-panel front and back (each: two stiles, three rails, two panels) and plain
    ends, a bottom and a lid."""
    W, D, H = 914.4, 457.2, 482.6
    sw = 63.5; g = 9.525; pt = 6.35
    p = []
    for y0 in (0.0, D - T):
        p += [box(0, y0, 0, sw, T, H), box(W - sw, y0, 0, sw, T, H)]
        rails_z = [0.0, H - sw]
        p += [box(sw, y0, 0, W - 2 * sw, T, sw), box(sw, y0, H - sw, W - 2 * sw, T, sw)]
        # a muntin in the middle, upright
        mx = W / 2 - sw / 2
        p += [box(mx, y0, sw, sw, T, H - 2 * sw)]
        for x0, x1 in ((sw, mx), (mx + sw, W - sw)):
            p += [box(x0 - g, y0 + (T - pt) / 2, sw - g, x1 - x0 + 2 * g, pt, H - 2 * sw + 2 * g)]
    p += [box(0, T, 0, T, D - 2 * T, H), box(W - T, T, 0, T, D - 2 * T, H)]          # ends
    p += [box(T, T, 50.8, W - 2 * T, D - 2 * T, T)]                                   # bottom on cleats? sits between
    p += [box(-12.7, -12.7, H, W + 25.4, D + 25.4, T)]                                # lid
    return p

write(os.path.join(out, "panel_chest.stl"), panel_chest())
print("panel_chest", len(panel_chest()))
