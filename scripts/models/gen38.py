import os, sys, math
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
T = 19.05

def poly_plinth(n=6, R=304.8):
    """A six-sided gallery plinth, 24 in across the corners, 36 in tall: six side panels with their long edges
    bevelled 30 degrees so they meet in mitres, and a hexagonal top sitting on them."""
    H = 914.4
    p = []
    for k in range(n):
        a0 = 2 * math.pi * k / n; a1 = 2 * math.pi * (k + 1) / n
        o0 = (R * math.cos(a0), R * math.sin(a0)); o1 = (R * math.cos(a1), R * math.sin(a1))
        ri = R - T / math.cos(math.pi / n)
        i0 = (ri * math.cos(a0), ri * math.sin(a0)); i1 = (ri * math.cos(a1), ri * math.sin(a1))
        poly = [o0, o1, i1, i0]
        # counter-clockwise in x,y
        cx = sum(q[0] for q in poly) / 4; cy = sum(q[1] for q in poly) / 4
        poly.sort(key=lambda q: math.atan2(q[1] - cy, q[0] - cx))
        p.append(prism(poly, 0, H - T))
    top = [(R * math.cos(2 * math.pi * k / n), R * math.sin(2 * math.pi * k / n)) for k in range(n)]
    p.append(prism(top, H - T, T))
    return p

write(os.path.join("stl", "hex_plinth.stl"), poly_plinth(6)); write(os.path.join("stl", "oct_plinth.stl"), poly_plinth(8, 254.0)); print("hex_plinth oct_plinth")
