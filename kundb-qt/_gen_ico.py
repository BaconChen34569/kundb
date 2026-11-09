# -*- coding: utf-8 -*-
"""Generate kundb.ico (multi-size, 32bpp BGRA) with pure Python - a blue rounded
square with a white database cylinder. No PIL dependency."""
import struct, math, os

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

BG_TOP = (36, 130, 226)    # blue
BG_BOT = (16, 74, 150)     # darker blue
WHITE = (255, 255, 255)

def coverage(x, y, S):
    """anti-aliased 1.0 = inside rounded square (alpha), else 0..1"""
    n = 2  # 2x2 supersampling
    total = 0.0
    for sy in range(n):
        for sx in range(n):
            px = x + (sx + 0.5) / n
            py = y + (sy + 0.5) / n
            total += 1.0 if in_rounded(px, py, S) else 0.0
    return total / (n * n)

def cyl_coverage(x, y, S):
    """anti-aliased 1.0 = inside white cylinder shape"""
    n = 2
    total = 0.0
    for sy in range(n):
        for sx in range(n):
            px = x + (sx + 0.5) / n
            py = y + (sy + 0.5) / n
            total += sample(px, py, S)
    return total / (n * n)

def in_rounded(px, py, S):
    # rounded-square background
    r = S * 0.22
    x0, y0, x1, y1 = r, r, S - r, S - r
    if px < x0: qx = x0
    elif px > x1: qx = x1
    else: qx = px
    if py < y0: qy = y0
    elif py > y1: qy = y1
    else: qy = py
    return math.hypot(px - qx, py - qy) <= r

def sample(px, py, S):
    """1.0 if inside the white cylinder shape (used for color only)"""
    # white database cylinder
    cx = S * 0.5
    rx = S * 0.30
    ry = S * 0.105
    topY = S * 0.30
    botY = S * 0.74
    in_top = ellipse_in(px, py, cx, topY, rx, ry)
    in_bot = ellipse_in(px, py, cx, botY, rx, ry)
    in_body = (abs(px - cx) <= rx) and (topY <= py <= botY)
    if not (in_top or in_bot or in_body):
        return 0.0
    # carve separation gap between top ellipse and body (thin line)
    gap = max(1.0, S * 0.035)
    if in_body and not in_top and abs(py - topY) <= gap / 2 and not in_bot:
        return 0.0
    return 1.0

def ellipse_in(px, py, cx, cy, rx, ry):
    if rx <= 0 or ry <= 0: return False
    dx = (px - cx) / rx
    dy = (py - cy) / ry
    return dx * dx + dy * dy <= 1.0

def render(S):
    rows = []  # top-down RGBA rows
    for y in range(S):
        row = []
        for x in range(S):
            a = coverage(x, y, S)
            if a <= 0.0:
                row.append((0, 0, 0, 0))
                continue
            t = y / max(1, S - 1)
            bg = lerp(BG_TOP, BG_BOT, t)
            cyl = cyl_coverage(x, y, S) >= 0.5
            col = WHITE if cyl else bg
            row.append((col[0], col[1], col[2], int(round(255 * a))))
        rows.append(row)
    return rows

def bmp_data(rows):
    S = len(rows)
    # 32bpp BGRA bottom-up + all-zero AND mask
    pix = bytearray()
    for y in range(S - 1, -1, -1):
        for x in range(S):
            r, g, b, a = rows[y][x]
            pix += struct.pack('<BBBB', b, g, r, a)
    mask_row_bytes = ((S + 31) // 32) * 4
    mask = bytes(mask_row_bytes * S)
    header = struct.pack('<IiiHHIIiiII', 40, S, S * 2, 1, 32, 0,
                         len(pix) + len(mask), 0, 0, 0, 0)
    return header + bytes(pix) + mask

def main():
    sizes = [16, 24, 32, 48, 64, 128, 256]
    imgs = []
    for s in sizes:
        imgs.append((s, bmp_data(render(s))))
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'kundb.ico')
    with open(out, 'wb') as f:
        f.write(struct.pack('<HHH', 0, 1, len(imgs)))
        offset = 6 + 16 * len(imgs)
        for s, data in imgs:
            f.write(struct.pack('<BBBBHHII', s % 256, s % 256, 0, 0, 1, 32,
                                len(data), offset))
            offset += len(data)
        for s, data in imgs:
            f.write(data)
    print('WROTE', out, os.path.getsize(out), 'bytes')

if __name__ == '__main__':
    main()
