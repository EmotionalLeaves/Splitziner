import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def ff_base():
    """A 30 in kitchen base with a face frame, one inset drawer over two inset doors, toe kick, stretchers."""
    W, D, H, kick, ff = 762.0, 584.2, 876.3, 114.3, 38.1
    p = [box(0, T, 0, T, D - T, H), box(W - T, T, 0, T, D - T, H)]                # sides behind the frame
    p += [box(T, T, kick, W - 2 * T, D - T - 6.35, T)]                              # bottom
    p += [box(T, T + 76.2, 0, W - 2 * T, T, kick)]                                  # toe kick
    p += [box(T, T, H - T, W - 2 * T, 101.6, T), box(T, D - 101.6 - 6.35, H - T, W - 2 * T, 101.6, T)]  # stretchers
    p += [box(T, D - 6.35, kick, W - 2 * T, 6.35, H - kick)]                        # back
    # face frame: stiles full height from kick, rails top, mid, bottom
    z0 = kick
    p += [box(0, 0, z0, ff, T, H - z0), box(W - ff, 0, z0, ff, T, H - z0)]
    p += [box(ff, 0, H - ff, W - 2 * ff, T, ff), box(ff, 0, H - ff - 152.4 - ff, W - 2 * ff, T, ff), box(ff, 0, z0, W - 2 * ff, T, ff + T)]
    # inset drawer front in the top opening, drawer box behind
    ow = W - 2 * ff; oz = H - ff - 152.4
    p += [box(ff + 1.6, 0, oz + 1.6, ow - 3.2, T, 152.4 - 3.2)]
    drawer(p, ff + 12.7, oz + 12.7, ow - 25.4, 152.4 - 38.1, 508.0, front_y=T)
    # two inset doors in the lower opening
    lz = z0 + ff + T; lh = oz - ff - lz
    dw = ow / 2
    p += [box(ff + 1.6, 0, lz + 1.6, dw - 2.4, T, lh - 3.2), box(ff + dw + 0.8, 0, lz + 1.6, dw - 2.4, T, lh - 3.2)]
    return p

write(os.path.join(out, "ff_base.stl"), ff_base())
print("ff_base", len(ff_base()))
