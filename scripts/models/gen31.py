import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def vitrine():
    """A display cabinet: a case with two glass shelves? no - wooden shelves, and two frame doors glazed with 4 mm glass set in a rabbet at the back of the frame."""
    W, D, H = 762.0, 355.6, 1219.2
    p = [box(0, 0, 0, T, D, H), box(W - T, 0, 0, T, D, H), box(T, 0, 0, W - 2 * T, D - 6.35, T), box(T, 0, H - T, W - 2 * T, D - 6.35, T)]
    p += [box(T, D - 6.35, 0, W - 2 * T, 6.35, H)]
    for z in (H / 3, 2 * H / 3): p += [box(T, 25.4, z, W - 2 * T, D - 6.35 - 25.4, T)]
    dw = W / 2 - 3.2; dh = H - 3.2; sw = 50.8; g = 9.525; gl = 4.0
    for x0 in (1.6, W / 2 + 1.6):
        y0 = -T; z0 = 1.6
        p += [box(x0, y0, z0, sw, T, dh), box(x0 + dw - sw, y0, z0, sw, T, dh), box(x0 + sw, y0, z0, dw - 2 * sw, T, sw), box(x0 + sw, y0, z0 + dh - sw, dw - 2 * sw, T, sw)]
        # glass in a rabbet at the back of the frame: 4 mm thick, 6 mm in from the back face
        p += [box(x0 + sw - g, y0 + T - 6.0 - gl, z0 + sw - g, dw - 2 * sw + 2 * g, gl, dh - 2 * sw + 2 * g)]
    return p

write(os.path.join(out, "vitrine.stl"), vitrine()); print("vitrine", len(vitrine()))
