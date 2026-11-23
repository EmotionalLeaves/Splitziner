import os, sys, math
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
T = 19.05

def folding_screen():
    """A three-panel folding screen, 66 in tall, each panel 20 in wide: a frame of 1x3 stiles and rails (rails between
    the stiles) with a 1/4 in plywood skin set in a rabbet flush with the back. Panels stand edge to edge, as when folded flat open."""
    W, H, sw, sk = 508.0, 1676.4, 63.5, 6.35
    p = []
    for i in range(3):
        x0 = i * (W + 3.0)   # 3 mm hinge gap
        p += [box(x0, 0, 0, sw, T, H), box(x0 + W - sw, 0, 0, sw, T, H),
              box(x0 + sw, 0, 0, W - 2 * sw, T, sw), box(x0 + sw, 0, H - sw, W - 2 * sw, T, sw),
              box(x0 + sw, 0, H / 2 - sw / 2, W - 2 * sw, T, sw)]
        p += [box(x0 + sw, T, sw, W - 2 * sw, sk, H - 2 * sw)]  # skin behind the frame, inside the stiles' width? (behind)
    return p

write(os.path.join("stl", "folding_screen.stl"), folding_screen()); print("folding_screen")
