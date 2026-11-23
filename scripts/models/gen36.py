import os, sys, math
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
T = 19.05

def storage_plinth():
    """A gallery plinth 16 x 16 x 36 in with a door in its back and a shelf inside: the sides wrap the front and back
    (front and back sit between the sides), top sits on, bottom inside, a recessed base (toe kick) all round underneath."""
    W, D, H, kick = 406.4, 406.4, 914.4, 50.8
    z0 = kick
    p = [box(0, 0, z0, T, D, H - z0 - T), box(W - T, 0, z0, T, D, H - z0 - T),   # sides
         box(T, 0, z0, W - 2 * T, T, H - z0 - T),                               # front
         box(0, 0, H - T, W, D, T),                                               # top
         box(T, T, z0, W - 2 * T, D - 2 * T, T)]                                  # bottom
    # back: a fixed strip at top and bottom, a door between
    p += [box(T, D - T, z0, W - 2 * T, T, 76.2), box(T, D - T, H - T - 76.2, W - 2 * T, T, 76.2)]
    p += [box(T + 1.5, D - T, z0 + 76.2 + 1.5, W - 2 * T - 3, T, H - T - z0 - 2 * 76.2 - 3)]   # door, 1.5 mm gaps
    p += [box(T, T, z0 + 400, W - 2 * T, D - 2 * T - 2, T)]                    # shelf
    # recessed base frame under it, set in 38 mm
    s = 38.1
    p += [box(s, s, 0, W - 2 * s, T, kick), box(s, D - s - T, 0, W - 2 * s, T, kick),
          box(s, s + T, 0, T, D - 2 * s - 2 * T, kick), box(W - s - T, s + T, 0, T, D - 2 * s - 2 * T, kick)]
    return p

write(os.path.join("stl", "storage_plinth.stl"), storage_plinth()); print("storage_plinth")
