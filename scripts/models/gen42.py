import os, sys, math
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])

def display_wall():
    """A freestanding gallery wall, 4 ft wide and 8 ft tall: a 2x4 stud frame (top and bottom plates, studs 16 in apart)
    skinned both sides with 1/2 in plywood, standing on two feet that reach out front and back."""
    W, H, sw, sd, ply = 1219.2, 2438.4, 38.1, 88.9, 12.7
    fh, fl = 88.9, 762.0
    z0 = fh
    p = [box(0, ply, z0, W, sd, sw), box(0, ply, H - sw, W, sd, sw)]           # plates
    hs = H - z0 - 2 * sw
    xs = [0.0, 406.4 - sw / 2, 812.8 - sw / 2, W - sw]
    for x in xs: p.append(box(x, ply, z0 + sw, sw, sd, hs))                      # studs
    p += [box(0, 0, z0, W, ply, H - z0), box(0, ply + sd, z0, W, ply, H - z0)]  # skins
    D = 2 * ply + sd
    for x in (76.2, W - 76.2 - sw):
        p.append(box(x, D / 2 - fl / 2, 0, sw, fl, fh))                         # feet, under the plates
    return p

write(os.path.join("stl", "display_wall.stl"), display_wall()); print("display_wall")
