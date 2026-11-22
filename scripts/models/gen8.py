import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def l_desk():
    """An L-shaped corner desk: two tops meeting at the corner, panel ends at the far ends, a square leg under the corner, aprons."""
    A, B, D, H, tt = 1524.0, 1219.2, 609.6, 742.95, 25.4
    leg = 63.5
    p = []
    p += [box(0, 0, H - tt, A, D, tt)]                                   # long top along x, at the back (y 0..D)
    p += [box(A - D, D, H - tt, D, B - D, tt)]                           # return top along y, at the right
    p += [box(0, 0, 0, T, D, H - tt)]                                    # left end panel
    p += [box(A - D, B - T, 0, D, T, H - tt)]                            # return end panel
    p += [box(A - leg, 0, 0, leg, leg, H - tt)]                          # corner leg at back right
    p += [box(T, D - 25.4 - T, H - tt - 101.6, A - D - T, T, 101.6)]     # front apron under long top
    p += [box(A - D - T + T, D, H - tt - 101.6, T, B - D - T, 101.6)]    # apron under return top (left edge)
    p += [box(T, 0, H - tt - 101.6, A - leg - T, T, 101.6)]              # back apron
    p += [box(A - T, leg, H - tt - 101.6, T, B - leg - T, 101.6)]        # right apron
    return p

write(os.path.join(out, "l_desk.stl"), l_desk())
print("l_desk", len(l_desk()))
