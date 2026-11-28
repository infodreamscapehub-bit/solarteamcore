#!/usr/bin/env python3
"""Genereaza texturile 16x16 (pixel-art original) pentru itemele de echipament FlowCity. Rulare: python3 tools/gen_item_textures.py <dir iesire>"""
import sys
from PIL import Image

OUT = sys.argv[1] if len(sys.argv) > 1 else "."
K = (22, 24, 30, 255)       # contur
W = (240, 240, 245, 255)
G1 = (70, 76, 90, 255)
G2 = (112, 120, 138, 255)
G3 = (168, 176, 192, 255)
R = (214, 56, 56, 255)
R2 = (150, 30, 36, 255)
B = (56, 120, 214, 255)
B2 = (30, 70, 140, 255)
Y = (245, 190, 60, 255)
Y2 = (200, 140, 30, 255)
GR = (60, 190, 110, 255)
GR2 = (30, 120, 70, 255)
O = (236, 128, 40, 255)
O2 = (170, 84, 20, 255)
P = (170, 90, 210, 255)
T = (0, 0, 0, 0)


class Ic:
    def __init__(self):
        self.im = Image.new("RGBA", (16, 16), T)

    def px(self, x, y, c):
        if 0 <= x < 16 and 0 <= y < 16:
            self.im.putpixel((x, y), c)

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.px(x, y, c)

    def box(self, x0, y0, x1, y1, fill, line=K):
        self.rect(x0, y0, x1, y1, line)
        self.rect(x0 + 1, y0 + 1, x1 - 1, y1 - 1, fill)

    def line(self, x0, y0, x1, y1, c):
        dx, dy = abs(x1 - x0), abs(y1 - y0)
        sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
        err = dx - dy
        while True:
            self.px(x0, y0, c)
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 > -dy:
                err -= dy
                x0 += sx
            if e2 < dx:
                err += dx
                y0 += sy

    def ring(self, cx, cy, r, c):
        for y in range(16):
            for x in range(16):
                d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
                if r - 0.6 <= d <= r + 0.6:
                    self.px(x, y, c)

    def disc(self, cx, cy, r, c):
        for y in range(16):
            for x in range(16):
                if ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5 <= r:
                    self.px(x, y, c)

    def save(self, name):
        self.im.save("%s/%s.png" % (OUT, name))


def catuse():
    i = Ic()
    i.ring(4.5, 9.5, 3.2, K); i.ring(4.5, 9.5, 2.4, G3)
    i.ring(11.5, 9.5, 3.2, K); i.ring(11.5, 9.5, 2.4, G3)
    i.rect(7, 8, 8, 10, K); i.rect(7, 9, 8, 9, G2)
    i.rect(3, 3, 5, 5, K); i.rect(4, 4, 4, 4, G2)
    i.rect(10, 3, 12, 5, K); i.rect(11, 4, 11, 4, G2)
    i.line(4, 6, 4, 6, K); i.line(11, 6, 11, 6, K)
    i.save("catuse")


def scanner_amprente():
    i = Ic()
    i.box(3, 1, 12, 14, G1)
    i.box(4, 2, 11, 9, B2)
    for y, w in ((3, 3), (4, 4), (5, 4), (6, 4), (7, 3)):
        i.rect(8 - w // 2, y, 7 + w // 2, y, B)
    i.line(6, 5, 9, 5, W); i.line(6, 7, 9, 7, W); i.px(5, 6, W); i.px(10, 6, W)
    i.rect(5, 11, 6, 12, GR); i.rect(9, 11, 10, 12, R)
    i.save("scanner_amprente")


def proba_amprenta():
    i = Ic()
    i.box(2, 3, 13, 14, W)
    i.rect(3, 3, 12, 5, G3)
    i.rect(5, 1, 10, 3, K); i.rect(6, 2, 9, 2, G3)
    for y, a, b in ((7, 6, 9), (8, 5, 10), (9, 5, 10), (10, 5, 10), (11, 6, 9), (12, 7, 8)):
        i.line(a, y, b, y, B2)
    i.line(7, 9, 8, 9, W); i.line(6, 11, 9, 11, W)
    i.save("proba_amprenta")


def bodycam():
    i = Ic()
    i.box(2, 4, 13, 12, G1)
    i.rect(3, 5, 12, 6, G2)
    i.disc(8, 9, 2.4, K); i.disc(8, 9, 1.5, B); i.px(8, 8, W)
    i.rect(10, 5, 12, 5, R)
    i.rect(5, 13, 10, 14, K); i.rect(6, 13, 9, 13, G2)
    i.save("bodycam")


def radio():
    i = Ic()
    i.rect(10, 0, 11, 4, K); i.rect(11, 0, 11, 3, G2)
    i.box(4, 4, 12, 15, G1)
    i.rect(5, 5, 11, 7, GR2); i.rect(6, 6, 9, 6, GR)
    for y in (9, 11, 13):
        i.line(6, y, 10, y, G3)
    i.rect(12, 8, 13, 10, R)
    i.save("radio")


def geanta_medicala():
    i = Ic()
    i.rect(5, 2, 10, 3, K); i.rect(6, 3, 9, 3, G2)
    i.box(1, 4, 14, 14, R, K)
    i.rect(2, 5, 13, 5, R2 if False else (240, 90, 90, 255))
    i.rect(6, 6, 9, 12, W); i.rect(4, 8, 11, 10, W)
    i.line(1, 9, 14, 9, R2) if False else None
    i.save("geanta_medicala")


def defibrilator():
    i = Ic()
    i.box(2, 3, 13, 13, Y)
    i.rect(3, 4, 12, 5, Y2)
    i.rect(4, 6, 11, 10, K)
    for x, y in ((5, 8), (6, 8), (7, 7), (8, 9), (9, 6), (10, 8)):
        i.px(x, y, GR)
    i.rect(4, 11, 6, 12, R); i.rect(9, 11, 11, 12, R)
    i.rect(0, 5, 1, 7, K); i.rect(14, 5, 15, 7, K)
    i.save("defibrilator")


def scanner_diagnoza():
    i = Ic()
    i.box(3, 1, 12, 10, G1)
    i.box(4, 2, 11, 7, K)
    i.line(5, 6, 6, 4, GR); i.line(6, 4, 7, 6, GR); i.line(7, 6, 8, 3, GR); i.line(8, 3, 10, 6, GR)
    i.rect(5, 9, 6, 9, R); i.rect(8, 9, 9, 9, Y)
    i.rect(7, 11, 8, 13, K); i.rect(5, 13, 10, 15, K); i.rect(6, 14, 9, 14, G2)
    i.save("scanner_diagnoza")


def trusa_reparatii():
    i = Ic()
    i.rect(5, 2, 10, 3, K); i.rect(6, 2, 9, 2, G2)
    i.box(1, 4, 14, 14, O, K)
    i.rect(2, 5, 13, 6, (250, 160, 70, 255))
    i.rect(1, 8, 14, 8, O2)
    i.rect(6, 7, 9, 9, Y2)
    i.line(4, 11, 11, 11, G3)
    i.save("trusa_reparatii")


def piesa_auto():
    i = Ic()
    i.disc(8, 8, 6.2, K); i.disc(8, 8, 5.2, G2)
    for a in range(8):
        import math
        x = 8 + round(6.4 * math.cos(a * math.pi / 4)); y = 8 + round(6.4 * math.sin(a * math.pi / 4))
        i.rect(x - 1, y - 1, x, y, K)
    i.disc(8, 8, 2.6, K); i.disc(8, 8, 1.6, G3)
    i.save("piesa_auto")


def camera_foto():
    i = Ic()
    i.box(1, 4, 14, 13, G1)
    i.rect(2, 5, 13, 6, G2)
    i.rect(10, 2, 13, 4, K); i.rect(11, 3, 12, 3, Y)
    i.disc(7.5, 9, 3.4, K); i.disc(7.5, 9, 2.6, B2); i.disc(7.5, 9, 1.4, B); i.px(6, 8, W)
    i.rect(2, 7, 3, 8, R)
    i.save("camera_foto")


def fotografie():
    i = Ic()
    i.box(2, 1, 13, 14, W)
    i.rect(3, 2, 12, 10, G2)
    i.rect(3, 7, 12, 10, GR2); i.rect(4, 8, 8, 10, GR)
    i.disc(10, 5, 1.5, Y)
    i.rect(5, 12, 10, 12, G3)
    i.save("fotografie")


for f in (catuse, scanner_amprente, proba_amprenta, bodycam, radio, geanta_medicala, defibrilator, scanner_diagnoza,
          trusa_reparatii, piesa_auto, camera_foto, fotografie):
    f()
