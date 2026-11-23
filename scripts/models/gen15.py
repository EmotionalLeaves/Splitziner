import math, os, sys
sys.argv = [sys.argv[0], "/tmp/junkstl"]
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
out = "stl"

def end_table():
    """A Shaker end table: four legs, side and back aprons, and at the front a top rail and a bottom rail with a
    drawer between them. The drawer runs on two runners screwed to the inside of the side aprons, with kickers above
    to stop it tipping. The top overhangs all round."""
    W, D, H, leg = 508.0, 457.2, 660.4, 38.1
    p = []
    for x in (0, W - leg):
        for y in (0, D - leg):
            p.append(box(x, y, 0, leg, leg, H - 19.05))
    az = H - 19.05 - 139.7; ah = 139.7; at = 19.05
    for x in (leg - at, W - leg):
        p.append(box(x, leg, az, at, D - 2 * leg, ah))                             # side aprons, flush with the legs' inner faces
    p.append(box(leg, D - leg, az, W - 2 * leg, at, ah))                            # back apron (flush with the back of legs? inside)
    p.append(box(leg, 0, az, W - 2 * leg, 38.1, 19.05))                             # front bottom rail, 3/4 x 1-1/2 lying flat
    p.append(box(leg, 0, az + ah - 19.05, W - 2 * leg, 38.1, 19.05))                 # front top rail
    # runners and kickers on the inside of the side aprons, behind the rails
    for x in (leg, W - leg - 19.05):
        p.append(box(x, 38.1, az, 19.05, D - leg - 38.1, 19.05))                    # runner
        p.append(box(x, 38.1, az + ah - 19.05, 19.05, D - leg - 38.1, 19.05))       # kicker
    # drawer: as wide as the opening between the legs, its sides riding on the runners
    ox0, ox1 = leg + 1.6, W - leg - 1.6
    oz0, oz1 = az + 19.05, az + ah - 19.05
    drawer(p, ox0, oz0, ox1 - ox0, oz1 - oz0 - 3.2, 381.0, front_y=19.05, t=12.7, bt=6.35,
           fx=ox0, fz=oz0 + 0.0, fw=ox1 - ox0, fh=oz1 - oz0 - 1.6, ft=19.05)
    p.append(box(-25.4, -25.4, H - 19.05, W + 50.8, D + 50.8, 19.05))
    return p

write(os.path.join(out, "end_table.stl"), end_table())
print("end_table", len(end_table()))
