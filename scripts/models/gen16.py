import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def chest():
    """A blanket chest: front, back and ends on a plinth, a bottom inside, and a lid overhanging the case on
    three sides, resting on its top edges (hinged at the back)."""
    W, D, H, pl = 914.4, 457.2, 482.6, 76.2
    p = []
    p += [box(0, 0, pl, W, T, H - pl), box(0, D - T, pl, W, T, H - pl)]            # front, back
    p += [box(0, T, pl, T, D - 2 * T, H - pl), box(W - T, T, pl, T, D - 2 * T, H - pl)]  # ends between
    p += [box(T, T, pl, W - 2 * T, D - 2 * T, T)]                                    # bottom
    # plinth: a frame of boards under the case, set in 12.7 mm
    s = 12.7
    p += [box(s, s, 0, W - 2 * s, T, pl), box(s, D - s - T, 0, W - 2 * s, T, pl)]
    p += [box(s, s + T, 0, T, D - 2 * s - 2 * T, pl), box(W - s - T, s + T, 0, T, D - 2 * s - 2 * T, pl)]
    p += [box(-19.05, -19.05, H, W + 38.1, D + 19.05, T)]                            # lid, flush at the back
    return p

write(os.path.join(out, "chest.stl"), chest())
print("chest", len(chest()))
