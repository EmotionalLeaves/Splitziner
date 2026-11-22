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
