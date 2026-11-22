import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def ccw(poly):
    a = sum(poly[i][0] * poly[(i + 1) % len(poly)][1] - poly[(i + 1) % len(poly)][0] * poly[i][1] for i in range(len(poly)))
    return poly if a > 0 else poly[::-1]

def mitred_box():
    """An open-top box whose four sides meet in 45 degree mitres, with a bottom sitting inside them."""
    W, D, H, t = 457.2, 304.8, 152.4, 19.05
    p = [prism(ccw([(0, 0), (W, 0), (W - t, t), (t, t)]), 0, H),
         prism(ccw([(t, D - t), (W - t, D - t), (W, D), (0, D)]), 0, H),
         prism(ccw([(0, 0), (t, t), (t, D - t), (0, D)]), 0, H),
         prism(ccw([(W, 0), (W, D), (W - t, D - t), (W - t, t)]), 0, H)]
    p.append(box(t, t, 0, W - 2 * t, D - 2 * t, 6.35))
    return p

def picture_frame():
    """A 16 x 20 in picture frame of 2 in wide, 3/4 in thick moulding mitred at the corners, lying flat."""
    W, H, w, t = 406.4, 508.0, 50.8, 19.05
    return [prism(ccw([(0, 0), (W, 0), (W - w, w), (w, w)]), 0, t),
            prism(ccw([(w, H - w), (W - w, H - w), (W, H), (0, H)]), 0, t),
            prism(ccw([(0, 0), (w, w), (w, H - w), (0, H)]), 0, t),
            prism(ccw([(W, 0), (W, H), (W - w, H - w), (W - w, w)]), 0, t)]

for fn in (mitred_box, picture_frame):
    write(os.path.join(out, fn.__name__ + ".stl"), fn())
    print(fn.__name__, len(fn()))
