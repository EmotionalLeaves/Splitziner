import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def gallery_plinth():
    """A gallery plinth (display pedestal): four MDF sides butted round a top set in flush, a ballast shelf low inside on cleats, open bottom."""
    W, D, H = 406.4, 406.4, 1066.8
    p = [box(0, 0, 0, W, T, H), box(0, D - T, 0, W, T, H), box(0, T, 0, T, D - 2 * T, H), box(W - T, T, 0, T, D - 2 * T, H)]
    p += [box(T, T, H - T, W - 2 * T, D - 2 * T, T)]                       # top, inset flush
    p += [box(T, T, H - T - 19.05, W - 2 * T, 19.05, 19.05), box(T, D - T - 19.05, H - T - 19.05, W - 2 * T, 19.05, 19.05)]  # top cleats
    p += [box(T, T, 101.6, W - 2 * T, 19.05, 19.05), box(T, D - T - 19.05, 101.6, W - 2 * T, 19.05, 19.05)]                  # shelf cleats
    p += [box(T, T, 101.6 + 19.05, W - 2 * T, D - 2 * T, T)]               # ballast shelf
    return p

def art_crate():
    """A shipping crate for a framed painting: plywood faces screwed to a 1x4 frame, a lid screwed down, battens on the outside."""
    W, D, H = 1016.0, 152.4, 812.8
    pt = 12.7; fw = 88.9
    p = []
    # 1x4 frame (on edge) all round, between front and back plywood
    p += [box(0, pt, 0, W, D - 2 * pt, T), box(0, pt, H - T, W, D - 2 * pt, T), box(0, pt, T, T, D - 2 * pt, H - 2 * T), box(W - T, pt, T, T, D - 2 * pt, H - 2 * T)]
    p += [box(0, 0, 0, W, pt, H), box(0, D - pt, 0, W, pt, H)]            # plywood back and lid (front)
    # battens on the lid side to stiffen and lift
    for x in (0.0, W / 2 - fw / 2, W - fw):
        p += [box(x, -T, 0, fw, T, H)]
    return p

for fn in (gallery_plinth, art_crate):
    write(os.path.join(out, fn.__name__ + ".stl"), fn()); print(fn.__name__, len(fn()))
