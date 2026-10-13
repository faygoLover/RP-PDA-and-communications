"""Значки плат памяти для ретро-КПК (16×16): зелёная плата, золотые контакты снизу, чёрные микросхемы и цветная
метка объёма (2 КБ — белая, 4 — голубая, 8 — жёлтая, 16 — красная; больше объём — больше микросхем).

Запуск: python scripts/gen_memory_boards.py → textures/item/memory_<kb>.png и models/item/memory_<kb>.json
"""
import json
import os

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "rppda")
PCB, PCB_HI, PCB_LO = (46, 120, 62, 255), (84, 160, 96, 255), (24, 74, 36, 255)
GOLD, GOLD_LO = (222, 178, 64, 255), (150, 112, 34, 255)
CHIP, CHIP_HI = (28, 28, 32, 255), (70, 70, 78, 255)
TRACE = (110, 176, 110, 255)
LABEL = {2: (236, 236, 228, 255), 4: (110, 200, 255, 255), 8: (255, 214, 80, 255), 16: (240, 80, 70, 255)}
CHIPS = {2: 1, 4: 2, 8: 2, 16: 3}

for kb in (2, 4, 8, 16):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = im.load()
    # Плата: x 1–14, y 2–13; светлый верх/левый край, тёмный низ/правый.
    for y in range(2, 14):
        for x in range(1, 15):
            px[x, y] = PCB
    for x in range(1, 15):
        px[x, 2] = PCB_HI
        px[x, 13] = PCB_LO
    for y in range(2, 14):
        px[1, y] = PCB_HI
        px[14, y] = PCB_LO
    # Дорожки.
    for x in range(3, 13, 2):
        px[x, 10] = TRACE
    # Контакты снизу.
    for x in range(2, 14, 2):
        px[x, 12] = GOLD
        px[x, 13] = GOLD_LO
    # Микросхемы.
    n = CHIPS[kb]
    w = 3 if n == 3 else 4
    gap = 1 if n > 1 else 0
    total = n * w + (n - 1) * gap
    x0 = 1 + (14 - total) // 2
    for i in range(n):
        cx = x0 + i * (w + gap)
        for y in range(4, 9):
            for x in range(cx, cx + w):
                px[x, y] = CHIP
        for x in range(cx, cx + w):
            px[x, 4] = CHIP_HI
    # Метка объёма — в левом верхнем углу.
    for y in range(3, 5):
        for x in range(2, 4):
            px[x, y] = LABEL[kb]
    im.save(os.path.join(ROOT, "textures", "item", "memory_%d.png" % kb))
    with open(os.path.join(ROOT, "models", "item", "memory_%d.json" % kb), "w", encoding="utf-8") as f:
        json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": "rppda:item/memory_%d" % kb}}, f, indent=2)
        f.write("\n")
print("ok")
