# Mirrors a binary STL left to right (x to -x, winding kept outward): python3 scripts/mirror-stl.py in.stl out.stl.
# A model and its mirror image should get the same guide, left and right swapped.
import struct, sys, os
src, dst = sys.argv[1], sys.argv[2]
d = open(src,'rb').read()
n = struct.unpack('<I', d[80:84])[0]
out = bytearray(d[:84])
for i in range(n):
    o = 84 + 50*i
    vals = struct.unpack('<12f', d[o:o+48])
    v = [vals[3:6], vals[6:9], vals[9:12]]
    v = [(-p[0], p[1], p[2]) for p in v]
    v = [v[0], v[2], v[1]]
    out += struct.pack('<3f', 0, 0, 0)
    for p in v: out += struct.pack('<3f', *p)
    out += b'\0\0'
open(dst,'wb').write(out)
