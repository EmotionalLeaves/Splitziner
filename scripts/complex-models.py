"""Complex furniture test models as multi-body STL (mm). Each part is a closed box shell."""
import struct, sys, os

def box(x, y, z, w, d, h):
    a = [(x, y, z), (x + w, y, z), (x + w, y + d, z), (x, y + d, z),
         (x, y, z + h), (x + w, y, z + h), (x + w, y + d, z + h), (x, y + d, z + h)]
    f = [(0, 2, 1), (0, 3, 2), (4, 5, 6), (4, 6, 7), (0, 1, 5), (0, 5, 4),
         (1, 2, 6), (1, 6, 5), (2, 3, 7), (2, 7, 6), (3, 0, 4), (3, 4, 7)]
    return [(a[i], a[j], a[k]) for i, j, k in f]

def write(name, parts):
    tris = [t for p in parts for t in p]
    with open(name, "wb") as fh:
        fh.write(b"\0" * 80); fh.write(struct.pack("<I", len(tris)))
        for t in tris:
            fh.write(struct.pack("<3f", 0, 0, 0))
            for v in t: fh.write(struct.pack("<3f", *v))
            fh.write(b"\0\0")

def drawer(parts, x, z, w, h, d, front_y=0.0, t=12.7, bt=6.35, fx=None, fz=None, fw=None, fh=None, ft=19.05):
    """Drawer box (x..x+w, z..z+h, depth d starting at front_y) with an applied front in front of it."""
    y0 = front_y
    parts += [box(x, y0, z, t, d, h), box(x + w - t, y0, z, t, d, h),            # sides
              box(x + t, y0, z, w - 2 * t, t, h), box(x + t, y0 + d - t, z, w - 2 * t, t, h),  # ends
              box(x + t, y0 + t, z, w - 2 * t, d - 2 * t, bt)]                      # bottom
    if fw: parts.append(box(fx, y0 - ft, fz, fw, ft, fh))

T = 19.05

def dresser():
    W, D, H, kick = 1219.2, 508.0, 914.4, 101.6
    p = []
    p += [box(0, 0, 0, T, D, H), box(W - T, 0, 0, T, D, H)]                       # sides
    p += [box(T, 0, H - T, W - 2 * T, D, T), box(T, 0, kick, W - 2 * T, D - 6.35, T)]  # top, bottom
    p += [box(W / 2 - T / 2, 0, kick + T, T, D - 6.35, H - kick - 2 * T)]          # centre divider
    p += [box(T, 50.8, 0, W - 2 * T, T, kick)]                                     # recessed kick board
    p += [box(T, D - 6.35, kick, W - 2 * T, 6.35, H - kick - T)]                   # back
    # 2 columns x 3 drawers, 1/2 in slide clearance each side, overlay fronts
    inner_h = H - kick - 2 * T
    col_w = (W - 3 * T) / 2
    for c in range(2):
        x0 = T + c * (col_w + T)
        for r in range(3):
            oh = inner_h / 3
            z0 = kick + T + r * oh
            drawer(p, x0 + 12.7, z0 + 12.7, col_w - 25.4, oh - 38.1, 457.2, front_y=0.0,
                   fx=x0 - T / 2 + 1.6 + (T / 2 if c == 0 else 0), fz=z0 + 1.6 + (0 if r else -T / 2), fw=col_w + T / 2 - 3.2, fh=oh - 3.2)
    return p

def kitchen_base():
    W, D, H, kick, kd = 762.0, 584.2, 876.3, 101.6, 76.2
    p = []
    p += [box(0, 0, 0, T, D, H), box(W - T, 0, 0, T, D, H)]
    p += [box(T, 0, kick, W - 2 * T, D - 6.35, T)]                                 # bottom
    p += [box(T, kd, 0, W - 2 * T, T, kick)]                                      # toe kick, set back
    p += [box(T, 0, H - T, W - 2 * T, 101.6, T), box(T, D - 6.35 - 101.6, H - T, W - 2 * T, 101.6, T)]  # stretchers
    p += [box(T, 0, H - 203.2, W - 2 * T, D - 6.35, T)]                           # drawer deck
    p += [box(T, 50.8, kick + T + 300, W - 2 * T, D - 6.35 - 50.8, T)]            # shelf (set back)
    p += [box(T, D - 6.35, kick, W - 2 * T, 6.35, H - kick)]                       # back
    drawer(p, T + 12.7, H - 203.2 + T + 6.35, W - 2 * T - 25.4, 127.0, 508.0,
           fx=1.6, fz=H - 203.2 + T / 2 + 1.6, fw=W - 3.2, fh=203.2 - T / 2 - 3.2)
    # two overlay doors below
    # overlay doors: they cover half the bottom's and the deck's front edges, 1/8 in gap between them and under the drawer front
    z0 = kick + T / 2; z1 = H - 203.2 + T / 2 - 1.6
    p += [box(1.6, -T, z0, W / 2 - 3.2, T, z1 - z0), box(W / 2 + 1.6, -T, z0, W / 2 - 3.2, T, z1 - z0)]
    return p

def bunk_bed():
    L, Wd, H, post = 2032.0, 990.6, 1651.0, 88.9
    p = []
    for x in (0, Wd - post):
        for y in (0, L - post):
            p.append(box(x, y, 0, post, post, H))
    for z in (304.8, 1219.2):                                                     # two decks
        # side rails between the posts, flush with their outer faces; end rails likewise
        p += [box(0, post, z, T, L - 2 * post, 184.15), box(Wd - T, post, z, T, L - 2 * post, 184.15)]
        p += [box(post, 0, z, Wd - 2 * post, T, 184.15), box(post, L - T, z, Wd - 2 * post, T, 184.15)]
        # cleats on the side rails' inner faces, slats resting on them
        p += [box(T, post, z + 50.8, T, L - 2 * post, 38.1), box(Wd - 2 * T, post, z + 50.8, T, L - 2 * post, 38.1)]
        n = 12
        gap = (L - 2 * post - n * 88.9) / (n - 1)
        for i in range(n):
            p.append(box(T, post + i * (88.9 + gap), z + 50.8 + 38.1, Wd - 2 * T, 88.9, T))
    # guard rails on the top bunk
    p += [box(0, post, 1524.0, T, L - 2 * post, 88.9), box(Wd - T, post, 1524.0, T, L - 2 * post, 88.9)]
    return p

def pedestal_desk():
    W, D, H, tt = 1524.0, 762.0, 742.95, 25.4
    lh = H - tt
    p = [box(0, 0, lh, W, D, tt)]                                                  # top
    pw = 457.2
    p += [box(0, 0, 0, T, D, lh), box(pw - T, 0, 0, T, D, lh)]                    # pedestal sides
    p += [box(T, 0, 76.2, pw - 2 * T, D - 6.35, T)]                                # pedestal bottom
    p += [box(T, 76.2, 0, pw - 2 * T, T, 76.2)]                                    # kick
    p += [box(T, D - 6.35, 76.2, pw - 2 * T, 6.35, lh - 76.2)]                     # pedestal back
    oh = (lh - 76.2 - T) / 3
    for r in range(3):
        z0 = 76.2 + T + r * oh
        drawer(p, T + 12.7, z0 + 12.7, pw - 2 * T - 25.4, oh - 38.1, 558.8, fx=T + 1.6, fz=z0 + 1.6, fw=pw - 2 * T - 3.2, fh=oh - 3.2)
    p += [box(W - T, 0, 0, T, D, lh)]                                              # end panel
    p += [box(pw, D - 3 * T, lh - 304.8, W - pw - T, T, 304.8)]                    # modesty panel
    return p

def bookcase():
    W, D, H = 914.4, 304.8, 1828.8
    p = [box(0, 0, 0, T, D, H), box(W - T, 0, 0, T, D, H)]
    p += [box(T, 0, 76.2, W - 2 * T, D - 6.35, T), box(T, 0, H - T, W - 2 * T, D - 6.35, T)]
    p += [box(T, 19.05, 0, W - 2 * T, T, 76.2)]                                    # kick
    for i in range(1, 5):
        p.append(box(T, 0, 76.2 + i * (H - 76.2) / 5, W - 2 * T, D - 6.35, T))
    p += [box(T, D - 6.35, 76.2, W - 2 * T, 6.35, H - 76.2)]
    # face frame: stiles and rails 1-1/2 wide on the front
    p += [box(0, -T, 0, 38.1, T, H), box(W - 38.1, -T, 0, 38.1, T, H)]
    p += [box(38.1, -T, 0, W - 76.2, T, 95.25), box(38.1, -T, H - 63.5, W - 76.2, T, 63.5)]
    # crown-ish top cap overhanging
    p += [box(-25.4, -T - 25.4, H, W + 50.8, D + T + 25.4, T)]
    return p

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
for fn in (dresser, kitchen_base, bunk_bed, pedestal_desk, bookcase):
    write(os.path.join(out, fn.__name__ + ".stl"), fn())
    print(fn.__name__, len(fn()), "parts")

import math

def prism(poly, z0, h):
    """Extruded convex polygon (counter-clockwise, in x,y) from z0 to z0+h."""
    n = len(poly); tris = []
    bot = [(x, y, z0) for x, y in poly]; top = [(x, y, z0 + h) for x, y in poly]
    for i in range(1, n - 1):
        tris.append((bot[0], bot[i + 1], bot[i])); tris.append((top[0], top[i], top[i + 1]))
    for i in range(n):
        j = (i + 1) % n
        tris.append((bot[i], bot[j], top[j])); tris.append((bot[i], top[j], top[i]))
    return tris

def rotated_box(cx, cy, cz, w, d, h, ax, ay):
    """Box centred at (cx,cy,cz), size w,d,h, tilted ax about x then ay about y (radians)."""
    b = box(-w / 2, -d / 2, -h / 2, w, d, h)
    def r(v):
        x, y, z = v
        y, z = y * math.cos(ax) - z * math.sin(ax), y * math.sin(ax) + z * math.cos(ax)
        x, z = x * math.cos(ay) + z * math.sin(ay), -x * math.sin(ay) + z * math.cos(ay)
        return (x + cx, y + cy, z + cz)
    return [tuple(r(v) for v in t) for t in b]

def corner_shelf():
    R, H = 406.4, 1219.2
    p = [box(0, 0, 0, T, R, H), box(T, 0, 0, R - T, T, H)]          # two backs meeting in the corner
    for i in range(5):
        z = 76.2 + i * (H - 76.2 - T) / 4
        p.append(prism([(T, T), (R, T), (T, R)], z, T))               # triangular shelves
    return p

def round_table():
    D, H, tt, leg = 914.4, 762.0, 25.4, 63.5
    n = 48
    poly = [(D / 2 + D / 2 * math.cos(2 * math.pi * i / n), D / 2 + D / 2 * math.sin(2 * math.pi * i / n)) for i in range(n)]
    p = [prism(poly, H - tt, tt)]
    off = D / 2 - 0.3 * D
    for x in (off, D - off - leg):
        for y in (off, D - off - leg):
            p.append(box(x, y, 0, leg, leg, H - tt))
    a = 76.2
    p += [box(off + leg, off, H - tt - a, D - 2 * off - 2 * leg, T, a), box(off + leg, D - off - T, H - tt - a, D - 2 * off - 2 * leg, T, a)]
    p += [box(off, off + leg, H - tt - a, T, D - 2 * off - 2 * leg, a), box(D - off - T, off + leg, H - tt - a, T, D - 2 * off - 2 * leg, a)]
    return p

def hexa(v):
    """Closed solid from 8 vertices ordered like box(): bottom 0-3, top 4-7."""
    f = [(0, 2, 1), (0, 3, 2), (4, 5, 6), (4, 6, 7), (0, 1, 5), (0, 5, 4),
         (1, 2, 6), (1, 6, 5), (2, 3, 7), (2, 7, 6), (3, 0, 4), (3, 4, 7)]
    return [(v[i], v[j], v[k]) for i, j, k in f]

def splayed_stool():
    S, H, tt, leg = 355.6, 609.6, 31.75, 38.1
    p = [box(0, 0, H - tt, S, S, tt)]
    out = (H - tt) * math.tan(math.radians(8))          # 8 degree splay, both ways
    for sx in (-1, 1):
        for sy in (-1, 1):
            cx = S / 2 + sx * (S / 2 - 45); cy = S / 2 + sy * (S / 2 - 45)
            bx = cx + sx * out; by = cy + sy * out
            h = leg / 2
            v = [(bx - h, by - h, 0), (bx + h, by - h, 0), (bx + h, by + h, 0), (bx - h, by + h, 0),
                 (cx - h, cy - h, H - tt), (cx + h, cy - h, H - tt), (cx + h, cy + h, H - tt), (cx - h, cy + h, H - tt)]
            p.append(hexa(v))
    # stretchers between the legs, low down, level
    z = 152.4; f = z / (H - tt)
    for sy in (-1, 1):
        cy = S / 2 + sy * (S / 2 - 45) + sy * out * (1 - f)
        xl = S / 2 - (S / 2 - 45) - out * (1 - f) + leg / 2; xr = S / 2 + (S / 2 - 45) + out * (1 - f) - leg / 2
        p.append(box(xl, cy - 9.5, z, xr - xl, 19.0, 38.1))
    return p

for fn in (corner_shelf, round_table, splayed_stool):
    write(os.path.join(out, fn.__name__ + ".stl"), fn())
    print(fn.__name__, len(fn()), "parts")
