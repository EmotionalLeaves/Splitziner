import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
exec(open("gen3.py").read().split("def mitred_box")[0].split('out = "stl"')[1])
out = "stl"

def prism_x(poly_yz, x0, w):
    """Extrude a polygon in the (y, z) plane along x from x0 to x0 + w."""
    tris = prism(ccw(poly_yz), x0, w)
    return [tuple((v[2], v[0], v[1]) for v in t) for t in tris]

def step_stool():
    """Two-step stool: sawtooth stringers, two treads on them, a back rail."""
    W, t = 406.4, 19.05
    rise, run = 228.6, 228.6
    # stringer outline in (y, z): back at y=2*run, front at y=0
    s = [(2 * run, 0), (2 * run, 2 * rise), (run, 2 * rise), (run, rise), (0, rise), (0, 0)]  # fan from a corner that sees all
    p = [prism_x(s, 0, t), prism_x(s, W - t, t)]
    p += [box(0, 0, rise, W, run, t), box(0, run, 2 * rise, W, run, t)]
    p += [box(t, 2 * run - t, 50.8, W - 2 * t, t, 2 * rise - 50.8)]
    return p

write(os.path.join(out, "step_stool.stl"), step_stool())
print("step_stool", len(step_stool()))
