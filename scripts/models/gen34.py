import os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
T = 19.05

def spice_rack():
    """A shallow wall rack: two sides, top, bottom, two shelves, a thin back, and a strip across the front of each shelf to keep jars in."""
    W, D, H = 609.6, 88.9, 609.6
    p = [box(0, 0, 0, T, D, H), box(W - T, 0, 0, T, D, H), box(T, 0, 0, W - 2 * T, D - 6.35, T), box(T, 0, H - T, W - 2 * T, D - 6.35, T), box(T, D - 6.35, 0, W - 2 * T, 6.35, H)]
    for z in (H / 3, 2 * H / 3): p.append(box(T, 0, z, W - 2 * T, D - 6.35, T))
    for z in (T, H / 3 + T, 2 * H / 3 + T): p.append(box(T, 0, z + 38, W - 2 * T, 6.35, 25.4))
    return p

write(os.path.join("stl", "spice_rack.stl"), spice_rack()); print("spice_rack")
