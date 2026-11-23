# Turns a binary STL a quarter turn about the vertical N times: python3 scripts/rotate-stl.py in.stl out.stl N.
# With the CLI's --up auto, a turned cabinet should come back facing the front.
import struct, sys
src, dst, k = sys.argv[1], sys.argv[2], int(sys.argv[3])
d = open(src,'rb').read(); n = struct.unpack('<I', d[80:84])[0]
out = bytearray(d[:84])
for i in range(n):
    o = 84 + 50*i; vals = struct.unpack('<12f', d[o:o+48])
    out += struct.pack('<3f', 0, 0, 0)
    for j in range(3):
        x, y, z = vals[3+3*j:6+3*j]
        for _ in range(k): x, y = -y, x
        out += struct.pack('<3f', x, y, z)
    out += b'\0\0'
open(dst,'wb').write(out)
