import os, sys, math
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
exec(open("gen37.py").read().split("def nesting_plinths")[0].split("T = 12.7")[1].join(["T = 12.7", ""]) if False else "")
T = 12.7
def cube(x0, s, h):
    return [box(x0, 0, 0, T, s, h - T), box(x0 + s - T, 0, 0, T, s, h - T),
            box(x0 + T, 0, 0, s - 2 * T, T, h - T), box(x0 + T, s - T, 0, s - 2 * T, T, h - T),
            box(x0, 0, h - T, s, s, T)]

def plinth_set():
    """Four plinths alike (12 x 12 x 36 in) and one wider (18 x 18 x 30 in), set out in a row 6 in apart."""
    p = []; x = 0.0
    for s, h in ((304.8, 914.4),) * 4 + ((457.2, 762.0),):
        p += cube(x, s, h); x += s + 152.4
    return p

write(os.path.join("stl", "plinth_set.stl"), plinth_set()); print("plinth_set")
