import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def flat_file():
    """A flat file for storing artwork: a carcass with five wide, shallow drawers on slides, each with an applied front, on a plinth."""
    W, D, H = 1016.0, 711.2, 457.2; kick = 76.2
    p = [box(0, 0, kick, T, D, H - kick), box(W - T, 0, kick, T, D, H - kick)]
    p += [box(T, 0, kick, W - 2 * T, D - 6.35, T), box(T, 0, H - T, W - 2 * T, D - 6.35, T), box(T, D - 6.35, kick, W - 2 * T, 6.35, H - kick)]
    p += [box(T, 50.8, 0, W - 2 * T, T, kick), box(T, D - 6.35 - T - 25.4, 0, W - 2 * T, T, kick), box(0, 50.8 + T, 0, T, D - 6.35 - 25.4 - 50.8 - 2 * T, kick), box(W - T, 50.8 + T, 0, T, D - 6.35 - 25.4 - 50.8 - 2 * T, kick)]
    n = 5; inner = H - kick - 2 * T; slot = inner / n
    for i in range(n):
        z = kick + T + i * slot
        drawer(p, T + 12.7, z + 6.35, W - 2 * T - 25.4, slot - 19.05, D - 6.35 - 50.8,
               fx=1.6, fz=z + 1.6 - (T / 2 if i == 0 else 0), fw=W - 3.2, fh=slot - 3.2 + (T / 2 if i == 0 else 0) + (T / 2 if i == n - 1 else 0))
    return p

write(os.path.join(out, "flat_file.stl"), flat_file()); print("flat_file", len(flat_file()))
