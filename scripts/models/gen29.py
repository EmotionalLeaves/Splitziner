import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def prism_x(x0, t, poly):
    """A prism t thick along x from x0, its profile `poly` (y, z) counter-clockwise, fan-triangulated (convex parts only used)."""
    a = [(x0, y, z) for y, z in poly]; b = [(x0 + t, y, z) for y, z in poly]
    n = len(poly); tris = []
    # ear-clip simple polygon
    idx = list(range(n))
    def area2(p, q, r): return (q[0]-p[0])*(r[1]-p[1]) - (q[1]-p[1])*(r[0]-p[0])
    def inside(pt, p, q, r):
        return area2(p, q, pt) >= 0 and area2(q, r, pt) >= 0 and area2(r, p, pt) >= 0
    faces = []
    while len(idx) > 3:
        for k in range(len(idx)):
            i0, i1, i2 = idx[k - 1], idx[k], idx[(k + 1) % len(idx)]
            p, q, r = poly[i0], poly[i1], poly[i2]
            if area2(p, q, r) <= 0: continue
            if any(inside(poly[j], p, q, r) for j in idx if j not in (i0, i1, i2)): continue
            faces.append((i0, i1, i2)); idx.pop(k); break
    faces.append(tuple(idx))
    for i, j, k in faces:
        tris += [(a[i], a[k], a[j]), (b[i], b[j], b[k])]
    for i in range(n):
        j = (i + 1) % n
        tris += [(a[i], a[j], b[j]), (a[i], b[j], b[i])]
    return tris

def step_stool():
    """A two-step stool: stepped plywood sides, two treads on top of the steps, a back panel and a kick rail."""
    W = 406.4; t = 19.05; d1, d2 = 228.6, 228.6; h1, h2 = 228.6, 457.2
    # side profile in (y, z): front at y=0, steps rising to the back
    prof = [(0, 0), (d1 + d2, 0), (d1 + d2, h2 - t), (d1, h2 - t), (d1, h1 - t), (0, h1 - t)]
    p = [prism_x(0, t, prof), prism_x(W - t, t, prof)]
    p += [box(0, 0, h1 - t, W, d1, t), box(0, d1, h2 - t, W, d2, t)]           # treads on the steps
    p += [box(t, d1 + d2 - t, 0, W - 2 * t, t, h2 - t)]                         # back panel between the sides
    p += [box(t, d1 - t, h1, W - 2 * t, t, h2 - h1 - t)]                        # riser under the upper step
    p += [box(t, 0, 0, W - 2 * t, t, 76.2)]                                     # kick rail at the front foot
    return p

write(os.path.join(out, "step_stool.stl"), step_stool()); print("step_stool", len(step_stool()))
