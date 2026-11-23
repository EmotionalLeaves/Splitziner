import os, sys, math
sys.argv=[sys.argv[0], "/tmp/junkstl"]
os.chdir(os.path.dirname(os.path.abspath(__file__)))
exec(open("gen.py").read().split("for fn in (kitchen_run")[0])
parts=[]
W=762.0; D=584.2
for k in range(3):
    for t in kitchen_base():
        parts.append([tuple((v[0]+D+k*W, v[1], v[2]) for v in tri) for tri in t])
# a run along y on the left: cabinets turned to face +x, starting behind... place along y from D onwards
for k in range(2):
    for t in kitchen_base():
        # rotate 90deg: front (-y) now faces +x: (x,y)->(y? ) use (x,y)->(-y,x)?? front at y=0 -> x=0 side facing -x; we want facing +x: (x,y)->(D - y, x)
        parts.append([tuple((D - v[1], D + k*W + v[0], v[2]) for v in tri) for tri in t])
# corner filler box in the corner (blind): a panel each way
parts.append(box(0, 0, 0, D, 19.05, 876.3)); parts.append(box(0, 19.05, 0, 19.05, D - 19.05, 876.3))
# L worktop as two pieces
parts.append(box(0, -25.4, 876.3, D + 3*W, D + 25.4, 31.75))
parts.append(box(0, D, 876.3, D + 25.4, 2*W, 31.75))
write(os.path.join("stl", "l_kitchen.stl"), parts); print(len(parts))
