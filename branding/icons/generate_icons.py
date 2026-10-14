#!/usr/bin/env python3
"""Placeholder 64x64 pixel art icons for the three KoperLib release jars.

Drawn on a 32x32 grid and scaled 2x. Plain Python, no Pillow: run `python3 generate_icons.py`
in this folder and it rewrites koperlib-core.png, koperlib-fullpack-apis.png, koperlib-khysics.png.
"""
import math
import struct
import zlib
from pathlib import Path

GRID = 32
SCALE = 2


def canvas():
    return [[(0, 0, 0, 0)] * GRID for _ in range(GRID)]


def rgb(hex_colour, alpha=255):
    h = hex_colour.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), alpha)


def put(img, x, y, colour):
    if 0 <= x < GRID and 0 <= y < GRID:
        img[y][x] = colour


def rect(img, x0, y0, x1, y1, colour):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            put(img, x, y, colour)


def frame(img, x0, y0, x1, y1, colour):
    for x in range(x0, x1 + 1):
        put(img, x, y0, colour)
        put(img, x, y1, colour)
    for y in range(y0, y1 + 1):
        put(img, x0, y, colour)
        put(img, x1, y, colour)


def line(img, x0, y0, x1, y1, colour):
    steps = max(abs(x1 - x0), abs(y1 - y0), 1)
    for i in range(steps + 1):
        put(img, round(x0 + (x1 - x0) * i / steps), round(y0 + (y1 - y0) * i / steps), colour)


def rounded_tile(img, base, edge):
    """The shared background: a rounded dark tile with a lighter rim."""
    for y in range(GRID):
        for x in range(GRID):
            corner = min(x, GRID - 1 - x) + min(y, GRID - 1 - y)
            if corner < 3:
                continue
            put(img, x, y, base)
    for i in range(3, GRID - 3):
        put(img, i, 1, edge)
        put(img, i, GRID - 2, edge)
        put(img, 1, i, edge)
        put(img, GRID - 2, i, edge)
    for x, y in ((2, 2), (GRID - 3, 2), (2, GRID - 3), (GRID - 3, GRID - 3)):
        put(img, x, y, edge)


def icon_core():
    """A chip: the library the other modules sit on, with pins for native loading and a lit core."""
    img = canvas()
    rounded_tile(img, rgb('1d2333'), rgb('3a4766'))
    pin = rgb('c9a227')
    for i in range(9, 23, 3):
        rect(img, i, 5, i + 1, 7, pin)
        rect(img, i, 24, i + 1, 26, pin)
        rect(img, 5, i, 7, i + 1, pin)
        rect(img, 24, i, 26, i + 1, pin)
    rect(img, 8, 8, 23, 23, rgb('2e3a55'))
    frame(img, 8, 8, 23, 23, rgb('5d6f99'))
    # the core: a green cube, the koper colour
    rect(img, 12, 12, 19, 19, rgb('3fae4a'))
    rect(img, 12, 12, 19, 13, rgb('7ee07f'))
    rect(img, 18, 12, 19, 19, rgb('2a7d33'))
    frame(img, 11, 11, 20, 20, rgb('14301a'))
    # render glint
    put(img, 13, 14, rgb('e8ffe8'))
    put(img, 14, 14, rgb('e8ffe8'))
    put(img, 13, 15, rgb('e8ffe8'))
    return img


def icon_fullpack():
    """A crate opened up with a page of JSON coming out: packs of content."""
    img = canvas()
    rounded_tile(img, rgb('2a1d33'), rgb('5a3f70'))
    # crate
    rect(img, 6, 15, 25, 26, rgb('8a5a2b'))
    for y in (18, 22):
        line(img, 6, y, 25, y, rgb('6b431f'))
    frame(img, 6, 15, 25, 26, rgb('3d2510'))
    line(img, 6, 15, 25, 26, rgb('6b431f'))
    # open lid
    rect(img, 4, 11, 13, 13, rgb('a8743d'))
    frame(img, 4, 11, 13, 13, rgb('3d2510'))
    # page with braces
    rect(img, 14, 4, 24, 16, rgb('f1ecdf'))
    frame(img, 14, 4, 24, 16, rgb('7d7566'))
    brace = rgb('9b4dca')
    for x, y in ((16, 6), (16, 7), (15, 8), (16, 9), (16, 10)):
        put(img, x, y, brace)
    for x, y in ((22, 6), (22, 7), (23, 8), (22, 9), (22, 10)):
        put(img, x, y, brace)
    for y in (7, 9):
        line(img, 18, y, 20, y, rgb('5c5c5c'))
    line(img, 16, 13, 22, 13, rgb('b8b0a0'))
    # a spark of effect
    for x, y in ((9, 6), (9, 8), (8, 7), (10, 7)):
        put(img, x, y, rgb('ffd34d'))
    put(img, 9, 7, rgb('fff6cc'))
    return img


def icon_khysics():
    """A block knocked loose and tumbling on an arc: blocks that move with physics."""
    img = canvas()
    rounded_tile(img, rgb('16263a'), rgb('2f5478'))
    # arc of motion
    for t in range(0, 70):
        a = math.radians(200 + t * 1.6)
        x = 16 + 12 * math.cos(a)
        y = 22 + 12 * math.sin(a)
        if t % 4 < 2:
            put(img, round(x), round(y), rgb('6fb3e8'))
    # ground blocks
    rect(img, 4, 25, 27, 27, rgb('5b8c3a'))
    rect(img, 4, 26, 27, 27, rgb('6b4a2b'))
    # the tilted block
    centre = (18.5, 12.5)
    ang = math.radians(25)
    top, side, dark = rgb('c8c8c8'), rgb('9a9a9a'), rgb('3a3a3a')
    for y in range(GRID):
        for x in range(GRID):
            dx, dy = x - centre[0], y - centre[1]
            u = dx * math.cos(ang) + dy * math.sin(ang)
            v = -dx * math.sin(ang) + dy * math.cos(ang)
            if abs(u) <= 5 and abs(v) <= 5:
                edge = abs(u) > 4.2 or abs(v) > 4.2
                put(img, x, y, dark if edge else (top if v < 0 else side))
    # speed lines behind it
    for i, y in enumerate((9, 12, 15)):
        line(img, 5, y + 1, 9 - i, y, rgb('a9d6ff'))
    return img


def write_png(path, img):
    size = GRID * SCALE
    raw = bytearray()
    for y in range(size):
        raw.append(0)
        row = img[y // SCALE]
        for x in range(size):
            raw.extend(row[x // SCALE])

    def chunk(kind, data):
        body = kind + data
        return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body) & 0xffffffff)

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')
    path.write_bytes(png)


if __name__ == '__main__':
    here = Path(__file__).resolve().parent
    for name, draw in (('koperlib-core', icon_core), ('koperlib-fullpack-apis', icon_fullpack),
                       ('koperlib-khysics', icon_khysics)):
        write_png(here / f'{name}.png', draw())
        print(f'wrote {name}.png')
