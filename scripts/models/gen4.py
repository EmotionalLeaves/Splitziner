import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def hutch_desk():
    """A desk on two panel ends with a modesty panel, and an open hutch of shelves sitting on the top at the back."""
    W, D, H = 1524.0, 609.6, 742.95
    p = [box(0, 0, 0, T, D, H - T), box(W - T, 0, 0, T, D, H - T)]           # ends
    p += [box(0, 0, H - T, W, D, T)]                                          # top
    p += [box(T, D - 50.8 - T, 254.0, W - 2 * T, T, H - T - 254.0)]          # modesty panel
    # hutch: 12 in deep, 36 in tall, at the back of the top
    hd, hh = 304.8, 914.4
    z0 = H; y0 = D - hd
    p += [box(0, y0, z0, T, hd, hh), box(W - T, y0, z0, T, hd, hh)]
    p += [box(T, y0, z0 + hh - T, W - 2 * T, hd - 6.35, T)]                  # hutch top
    p += [box(W / 2 - T / 2, y0, z0, T, hd - 6.35, hh - T)]                  # centre upright
    for z in (z0 + 381.0,):
        p += [box(T, y0, z, W / 2 - T / 2 - T, hd - 6.35, T), box(W / 2 + T / 2, y0, z, W / 2 - T / 2 - T, hd - 6.35, T)]
    p += [box(T, D - 6.35, z0, W - 2 * T, 6.35, hh - T)]                     # hutch back
    return p

write(os.path.join(out, "hutch_desk.stl"), hutch_desk())
print("hutch_desk", len(hutch_desk()))
