import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def cyl(cx, cy, cz, r, L, axis, n=24, tilt=0.0):
    """A cylinder from (cx,cy,cz) along -y (out of a wall board), tilted up by `tilt` degrees."""
    a = math.radians(tilt)
    d = (0.0, -math.cos(a), math.sin(a))
    # basis perpendicular to d
    u = (1.0, 0.0, 0.0); v = (0.0, math.sin(a), math.cos(a))
    ring0 = []; ring1 = []
    for i in range(n):
        t = 2 * math.pi * i / n
        o = tuple(r * (math.cos(t) * u[k] + math.sin(t) * v[k]) for k in range(3))
        ring0.append((cx + o[0], cy + o[1], cz + o[2]))
        ring1.append((cx + o[0] + L * d[0], cy + o[1] + L * d[1], cz + o[2] + L * d[2]))
    c0 = (cx, cy, cz); c1 = (cx + L * d[0], cy + L * d[1], cz + L * d[2])
    tris = []
    for i in range(n):
        j = (i + 1) % n
        tris += [(c0, ring0[j], ring0[i]), (c1, ring1[i], ring1[j]), (ring0[i], ring0[j], ring1[j]), (ring0[i], ring1[j], ring1[i])]
    return tris

def coat_rack():
    """A wall coat rack: a 1x6 board with five 3/4 in dowel pegs glued into holes, a shelf on top on two brackets."""
    W = 914.4; t = 19.05; h = 139.7
    p = [box(0, 0, 0, W, t, h)]
    for i in range(5):
        x = W / 6 * (i + 1)
        p.append(cyl(x, t * 0.5, h / 2, 9.525, 101.6 + t * 0.5, None))   # peg sunk half the board
    p.append(box(-25.4, -152.4, h, W + 50.8, 152.4 + t, t))                   # shelf on top
    for x in (76.2, W - 76.2 - t):
        p.append(box(x, -127.0, h - 101.6, t, 127.0, 101.6))                  # brackets under the shelf
    return p

write(os.path.join(out, "coat_rack.stl"), coat_rack()); print("coat_rack", len(coat_rack()))
