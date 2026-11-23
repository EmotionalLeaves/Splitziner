import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def island():
    """A kitchen island: a case with a drawer bank (3 drawers) on the left and an open bay with one shelf on the
    right, both opening to the front; a closed back; a top overhanging 12 in at the back for stools, held by two
    corbels (triangular brackets) screwed to the back panel."""
    W, D, H, kick = 1219.2, 609.6, 876.3, 101.6
    p = []
    p += [box(0, 0, 0, T, D, H - 31.75), box(W - T, 0, 0, T, D, H - 31.75)]          # sides
    p += [box(T, 0, kick, W - 2 * T, D - T, T)]                                       # bottom
    p += [box(T, 76.2, 0, W - 2 * T, T, kick)]                                        # toe kick
    p += [box(T, D - T, 0, W - 2 * T, T, H - 31.75)]                                   # thick back panel (seating side)
    p += [box(T, 0, H - 31.75 - T, W - 2 * T, 101.6, T), box(T, D - T - 101.6, H - 31.75 - T, W - 2 * T, 101.6, T)]  # stretchers
    xm = 609.6
    p += [box(xm, 0, kick + T, T, D - T, H - 31.75 - T - kick - T)]                    # divider
    p += [box(xm + T, 50.8, kick + T + 330.0, W - T - xm - T, D - T - 50.8, T)]        # shelf in the open bay
    # drawer bank: 3 drawers between the left side and the divider, runners on side-mounted slides
    ow = xm - T; zb = kick + T; zt = H - 31.75 - T
    hs = [(zt - zb) / 3.0] * 3
    z = zb
    for hh in hs:
        drawer(p, T + 12.7, z + 12.7, ow - 25.4, hh - 50.8, 508.0, fx=T / 2 + 1.6, fz=z + 1.6, fw=ow + T / 2 - 3.2 - T / 2, fh=hh - 3.2)
        z += hh
    # top: 1-1/4 in thick, overhang 1 in front and sides, 12 in at the back
    p += [box(-25.4, -25.4, H - 31.75, W + 50.8, D + 25.4 + 304.8, 31.75)]
    # corbels: right-triangle brackets under the overhang, against the back panel
    for x in (152.4, W - 152.4 - 38.1):
        p.append(prism([(D, H - 31.75), (D + 254.0, H - 31.75), (D, H - 31.75 - 254.0)], x, 38.1) if False else None)
    p = [q for q in p if q is not None]
    for x in (152.4, W - 152.4 - 38.1):
        # triangular prism in the y-z plane, thickness along x
        a = [(x, D, H - 31.75), (x, D + 254.0, H - 31.75), (x, D, H - 31.75 - 254.0)]
        b = [(x + 38.1, v[1], v[2]) for v in a]
        tris = [(a[0], a[2], a[1]), (b[0], b[1], b[2])]
        for i in range(3):
            j = (i + 1) % 3
            tris += [(a[i], a[j], b[j]), (a[i], b[j], b[i])]
        p.append(tris)
    return p

write(os.path.join(out, "island.stl"), island())
print("island", len(island()))
