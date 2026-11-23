import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def loft_bed():
    """A loft bed: four posts, one deck of slats on cleats high up, guard rails, a ladder of rungs between two
    uprights at the foot end of one side, and a desk top on cleats under the deck."""
    L, Wd, H, post = 2032.0, 990.6, 1828.8, 88.9
    p = []
    for x in (0, Wd - post):
        for y in (0, L - post):
            p.append(box(x, y, 0, post, post, H))
    z = 1371.6
    p += [box(0, post, z, T, L - 2 * post, 184.15), box(Wd - T, post, z, T, L - 2 * post, 184.15)]
    p += [box(post, 0, z, Wd - 2 * post, T, 184.15), box(post, L - T, z, Wd - 2 * post, T, 184.15)]
    p += [box(T, post, z + 50.8, T, L - 2 * post, 38.1), box(Wd - 2 * T, post, z + 50.8, T, L - 2 * post, 38.1)]
    n = 12; gap = (L - 2 * post - n * 88.9) / (n - 1)
    for i in range(n):
        p.append(box(T, post + i * (88.9 + gap), z + 50.8 + 38.1, Wd - 2 * T, 88.9, T))
    # guard rails, one on the wall side, one on the open side leaving the ladder gap
    p += [box(0, post, H - 88.9 - 38.1, T, L - 2 * post, 88.9)]
    p += [box(Wd - T, post + 457.2, H - 88.9 - 38.1, T, L - 2 * post - 457.2, 88.9)]
    # ladder: two uprights on the outside of the open side rail, rungs between them
    lx = Wd
    for y in (post + 25.4, post + 25.4 + 406.4 - 38.1):
        p.append(box(lx, y, 0, 38.1, 38.1, H - 88.9))
    for k in range(1, 6):
        p.append(box(lx, post + 25.4 + 38.1, k * 280.0, 38.1, 406.4 - 2 * 38.1, 38.1))
    # desk under the deck: top on two cleats screwed to the posts' inner faces at the head end
    dz = 736.6
    p += [box(post, post, dz - 38.1, T, 609.6, 38.1)]
    p += [box(Wd - post - T, post, dz - 38.1, T, 609.6, 38.1)]
    p += [box(post, post, dz, Wd - 2 * post, 609.6, T)]
    return p

write(os.path.join(out, "loft_bed.stl"), loft_bed())
print("loft_bed", len(loft_bed()))
