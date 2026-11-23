import os, sys, math
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
T = 12.7

def cube(x0, s, h):
    """An open-bottomed display cube: four sides (front and back between the sides) and a top sitting on them."""
    return [box(x0, 0, 0, T, s, h - T), box(x0 + s - T, 0, 0, T, s, h - T),
            box(x0 + T, 0, 0, s - 2 * T, T, h - T), box(x0 + T, s - T, 0, s - 2 * T, T, h - T),
            box(x0, 0, h - T, s, s, T)]

def nesting_plinths():
    """Three display cubes, 18, 15 and 12 in, set out side by side 4 in apart; each stores inside the next."""
    p = []; x = 0.0
    for s, h in ((457.2, 457.2), (381.0, 381.0), (304.8, 304.8)):
        p += cube(x, s, h); x += s + 101.6
    return p

write(os.path.join("stl", "nesting_plinths.stl"), nesting_plinths()); print("nesting_plinths")
