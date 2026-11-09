"""Pull comet mark: source geometry. Local frame points along +x, centred on 0,0; the mark is this rotated 45 deg.
Android vectors use the same local paths inside a rotated group, so keep both in step."""
from math import cos, sin, radians
import sys

HEAD = (38, 0, 44)                       # cx, cy, r
TRAILS = [(-102, -18, -12, 12),            # x0, x1, y0, y1: middle (longest)
          (-58, -2, -44, -24),            # upper
          (-58, -2, 24, 44)]              # lower

def rot(x, y, a=45, c=128):
    t = radians(a)
    return c + x*cos(t) - y*sin(t), c + x*sin(t) + y*cos(t)

def f(v): return f"{v:.2f}".rstrip("0").rstrip(".")

def svg_paths():
    cx, cy, r = HEAD
    hx, hy = rot(cx, cy)
    d = f"M{f(hx)} {f(hy-r)}A{r} {r} 0 1 1 {f(hx)} {f(hy+r)}A{r} {r} 0 1 1 {f(hx)} {f(hy-r)}Z"
    for x0, x1, y0, y1 in TRAILS:
        k = (y1-y0)/2
        p = [rot(x0+k, y0), rot(x1-k, y0), rot(x1-k, y1), rot(x0+k, y1)]
        d += (f"M{f(p[0][0])} {f(p[0][1])}L{f(p[1][0])} {f(p[1][1])}A{f(k)} {f(k)} 0 0 1 {f(p[2][0])} {f(p[2][1])}"
              f"L{f(p[3][0])} {f(p[3][1])}A{f(k)} {f(k)} 0 0 1 {f(p[0][0])} {f(p[0][1])}Z")
    return d

if __name__ == "__main__":
    color = sys.argv[1] if len(sys.argv) > 1 else "#000"
    print(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256"><title>Pull</title><path fill="{color}" d="{svg_paths()}"/></svg>')
