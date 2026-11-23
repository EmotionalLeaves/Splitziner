import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def picture_ledge():
    """A gallery picture ledge: a back board, a shelf screwed to its face part way up, a lip along the front."""
    W = 1219.2; t = 19.05
    p = [box(0, 0, 0, W, t, 101.6)]                                  # back board
    p += [box(0, -88.9, 25.4, W, 88.9, t)]                           # shelf against the back board's face
    p += [box(0, -88.9 - t, 25.4, W, t, 44.45)]                      # lip on the front edge, standing proud
    return p

write(os.path.join(out, "picture_ledge.stl"), picture_ledge()); print("picture_ledge", len(picture_ledge()))
