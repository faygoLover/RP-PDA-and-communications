"""Корпуса ретро-КПК: текстуры узорных корпусов, образцы для выбора и предметы под каждый корпус.

Текстура — весь корпус 360×254 в «пикселях интерфейса» (как его рисует окно при обычном размере; на маленьком
экране растягивается). Экран и клавиши рисует код поверх. Одноцветные корпуса текстур не имеют — их цвет в
Skins.java. Порядок и id — как в Look.BODIES.
Запуск: python scripts/gen_skins.py
"""
import json
import os
import random

from PIL import Image, ImageDraw

RES = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "rppda")
W, H = 360, 254
SX, SY, SW, SH = 18, 18, 324, 188  # экран (как в PdaScreen для ретро)
BOTTOM = SY + SH + 6  # низ рамки экрана

BAND = [(232, 96, 48), (240, 180, 60), (150, 162, 64), (56, 150, 150), (48, 72, 112)]
RAINBOW = [(244, 200, 60), (240, 150, 40), (226, 70, 50), (200, 40, 110), (120, 50, 150), (40, 70, 160), (40, 140, 90)]


def solid(c):
    def f(img):
        ImageDraw.Draw(img).rectangle([0, 0, W, H], fill=c)
    return f


def camo(img):
    rnd = random.Random(7)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, H], fill=(96, 104, 66))
    for col in [(70, 82, 48), (122, 112, 76), (48, 52, 36)]:
        for _ in range(28):
            x, y = rnd.randrange(-10, W), rnd.randrange(-10, H)
            for _ in range(5):
                rx, ry = rnd.randrange(6, 16), rnd.randrange(4, 10)
                d.ellipse([x, y, x + rx, y + ry], fill=col)
                x += rnd.randrange(-6, 9)
                y += rnd.randrange(-4, 5)


def wood(img):
    rnd = random.Random(3)
    px = img.load()
    for y in range(H):
        for x in range(W):
            t = (y + 3 * ((x // 37 + y // 7) % 3)) % 9
            base = 112 + (t < 2) * -18 + (t == 4) * 10
            n = rnd.randrange(-4, 5)
            px[x, y] = (base + 28 + n, base - 20 + n, base - 62 + n // 2, 255)


def duo(img):
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, H], fill=(214, 206, 188))
    d.rectangle([0, BOTTOM, W, H], fill=(70, 72, 78))
    d.rectangle([0, BOTTOM - 3, W, BOTTOM - 1], fill=(226, 120, 40))


def worn(img):
    rnd = random.Random(11)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, H], fill=(104, 108, 74))
    for _ in range(70):
        x, y = rnd.randrange(W), rnd.randrange(H)
        n = rnd.randrange(4, 14)
        d.line([(x, y), (x + n, y + rnd.randrange(-2, 3))], fill=(138, 140, 108))
    for _ in range(36):
        x, y = rnd.randrange(W), rnd.randrange(H)
        r = rnd.randrange(1, 4)
        d.ellipse([x, y, x + r, y + r], fill=(122, 82, 46) if rnd.random() < 0.6 else (150, 150, 130))


def field(img):
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, H], fill=(54, 56, 58))
    y0 = H - 7
    for x in range(-10, W + 10, 10):
        d.polygon([(x, y0), (x + 5, y0), (x + 9, H), (x + 4, H)], fill=(226, 190, 40))


def rainbow(img):
    rnd = random.Random(5)
    px = img.load()
    for y in range(H):
        for x in range(W):
            n = rnd.randrange(-5, 4)
            c = (232 + n, 222 + n, 196 + n)
            t = x + y * 0.62 - 230
            if t >= 0:
                k = int(t // 9)
                if k < len(RAINBOW) and t % 9 < 7.5:
                    r, g, b = RAINBOW[k]
                    c = (r + n, g + n, b + n)
            px[x, y] = c + (255,)


def gradient(img):
    px = img.load()
    stops = [(0, (36, 12, 66)), (0.45, (150, 36, 140)), (0.75, (240, 70, 120)), (1.0, (255, 160, 70))]
    for y in range(H):
        f = y / (H - 1)
        for i in range(len(stops) - 1):
            if stops[i][0] <= f <= stops[i + 1][0]:
                a, ca = stops[i]
                b, cb = stops[i + 1]
                k = (f - a) / (b - a)
                c = tuple(int(ca[j] + (cb[j] - ca[j]) * k) for j in range(3))
                break
        for x in range(W):
            px[x, y] = c + (255,)


def sunset(img):
    """«Закат», вариант А: полосы с трети высоты, ровный шаг, книзу толще."""
    gradient(img)
    d = ImageDraw.Draw(img)
    y, n = H * 0.35, 0
    while y < H:
        t = 1 + n // 3
        d.rectangle([0, int(y), W, int(y) + t - 1], fill=(40, 10, 60))
        y += 9
        n += 1


def neon(img):
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, H], fill=(24, 8, 44))
    vx = W // 2
    for i in range(-16, 17):
        d.line([(vx + i * 8, BOTTOM), (vx + i * 40, H)], fill=(255, 40, 180))
    y, step = BOTTOM, 2
    while y < H:
        d.line([(0, y), (W, y)], fill=(255, 40, 180))
        y += step
        step += 2
    d.rectangle([1, 1, W - 2, H - 2], outline=(0, 230, 255))


def memphis(img):
    rnd = random.Random(21)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, H], fill=(238, 236, 228))
    cols = [(0, 170, 170), (255, 80, 150), (250, 200, 30), (40, 40, 44), (90, 90, 230)]
    for _ in range(80):
        x, y = rnd.randrange(W), rnd.randrange(H)
        c = rnd.choice(cols)
        kind = rnd.randrange(4)
        if kind == 0:
            d.polygon([(x, y), (x + 6, y), (x + 3, y - 5)], fill=c)
        elif kind == 1:
            d.line([(x, y), (x + 3, y - 3), (x + 6, y), (x + 9, y - 3), (x + 12, y)], fill=c, width=1)
        elif kind == 2:
            d.ellipse([x, y, x + 3, y + 3], fill=c)
        else:
            d.line([(x, y), (x + 5, y + 5)], fill=c, width=2)


def cassette(img):
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, H], fill=(56, 56, 60))
    for i, c in enumerate([(250, 210, 40), (245, 150, 30), (230, 60, 50), (190, 30, 90)]):
        d.rectangle([0, H - 12 + i * 2, W, H - 11 + i * 2], fill=c)
        # Полосы по бокам — у верха экрана, от края корпуса до рамки экрана.
        y = SY + 10 + i * 3
        d.rectangle([0, y, SX, y + 1], fill=c)
        d.rectangle([W - SX - 1, y, W, y + 1], fill=c)


def band_path(d, segments):
    for i, c in enumerate(BAND):
        o = i * 3
        for seg in segments:
            kind = seg[0]
            if kind == "v":
                _, x, y0, y1, s = seg
                xx = x + s * o
                d.rectangle([min(xx, xx + s), min(y0, y1), max(xx, xx + s), max(y0, y1)], fill=c)
            elif kind == "h":
                _, y, x0, x1, s = seg
                yy = y + s * o
                d.rectangle([min(x0, x1), min(yy, yy + s), max(x0, x1), max(yy, yy + s)], fill=c)
            else:
                _, cx, cy, r, a0, a1 = seg
                rr = r - o
                d.arc([cx - rr, cy - rr, cx + rr, cy + rr], a0, a1, fill=c, width=2)


def wave(img):
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, H], fill=(236, 226, 204))
    R = 26
    band_path(d, [("v", 1, H, 1 + R, +1), ("arc", 1 + R, 1 + R, R, 180, 270), ("h", 1, 1 + R, 200, +1)])


def disco(img):
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, H], fill=(44, 30, 26))
    R = 24
    x, y = W - 2, H - 2
    band_path(d, [("v", x, 22, y - R, -1), ("arc", x - R, y - R, R, 0, 90), ("h", y, x - R, 0, -1)])


# id: (рисунок, текстура?, где взять образец (x, y))
BODIES = [
    ("beige", solid((192, 182, 158)), False, (0, 0)),
    ("graphite", solid((66, 68, 72)), False, (0, 0)),
    ("olive", solid((92, 98, 62)), False, (0, 0)),
    ("navy", solid((40, 54, 82)), False, (0, 0)),
    ("white", solid((222, 222, 214)), False, (0, 0)),
    ("khaki", solid((162, 142, 98)), False, (0, 0)),
    ("orange", solid((214, 112, 42)), False, (0, 0)),
    ("camo", camo, True, (40, 40)),
    ("wood", wood, True, (40, 40)),
    ("duo", duo, True, (100, 170)),
    ("worn", worn, True, (40, 40)),
    ("field", field, True, (100, 194)),
    ("rainbow", rainbow, True, (60, 194)),
    ("sunset", sunset, True, (0, 120)),
    ("neon", neon, True, (138, 194)),
    ("memphis", memphis, True, (40, 40)),
    ("cassette", cassette, True, (0, 194)),
    ("wave", wave, True, (0, 0)),
    ("disco", disco, True, (W - 84, H - 60)),
]


def chamfer(img):
    """Срезанные углы корпуса — прозрачные (как у одноцветного, который код рисует сам)."""
    px = img.load()
    for (x, y) in [(0, 0), (1, 0), (2, 0), (0, 1), (0, 2), (1, 1)]:
        for cx, cy in [(x, y), (W - 1 - x, y), (x, H - 1 - y), (W - 1 - x, H - 1 - y)]:
            px[cx, cy] = (0, 0, 0, 0)


def item_icon(body_img, path):
    """Предмет 16×16: корпус узором/цветом, зелёный экран, клавиши."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    pat = body_img.convert("RGB").resize((10, 14), Image.BOX)
    for y in range(1, 15):
        for x in range(3, 13):
            px[x, y] = pat.getpixel((x - 3, y - 1)) + (255,)
    edge_l = tuple(min(255, c + 30) for c in pat.getpixel((0, 0)))
    edge_d = tuple(max(0, c - 40) for c in pat.getpixel((9, 13)))
    for y in range(1, 15):
        px[3, y] = edge_l + (255,)
        px[12, y] = edge_d + (255,)
    for x in range(3, 13):
        px[x, 1] = edge_l + (255,)
        px[x, 14] = edge_d + (255,)
    for y in range(3, 10):
        for x in range(5, 11):
            px[x, y] = (24, 90, 44, 255)
    for x in range(5, 10):
        px[x, 4] = (110, 240, 130, 255)
    px[5, 6] = px[6, 6] = px[7, 6] = (90, 210, 110, 255)
    for x in (5, 7, 9):
        px[x, 11] = (40, 38, 36, 255)
        px[x + 1, 11] = (40, 38, 36, 255)
    px[12, 2] = (60, 255, 60, 255)
    img.save(path)


DISPLAY = {
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
    "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.55, 0.55, 0.55]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
    "fixed": {"rotation": [0, 180, 0], "scale": [1, 1, 1]},
}


def sheet(body_img, screen, keys, path, back_body=None):
    """Текстура 3D-модели 16×16: слева (8×14) — лицо с экраном, справа — задняя крышка."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    pat = body_img.convert("RGB").resize((8, 14), Image.BOX)
    back = (back_body or body_img).convert("RGB").resize((8, 14), Image.BOX)
    for y in range(14):
        for x in range(8):
            px[x, y + 1] = pat.getpixel((x, y)) + (255,)
            px[8 + x, y + 1] = back.getpixel((x, y)) + (255,)
    light, dark = screen
    for y in range(2, 8):
        for x in range(1, 7):
            px[x, y + 1] = dark + (255,)
    for x in range(2, 6):
        px[x, 4] = light + (255,)
    px[2, 6] = px[3, 6] = light + (255,)
    for x in (1, 3, 5):
        px[x, 11] = keys + (255,)
        px[x + 1, 11] = keys + (255,)
    px[6, 2] = (90, 255, 90, 255)
    # Сзади: крышка отсека и винты.
    cover = tuple(max(0, c - 34) for c in back.getpixel((4, 8)))
    for x in range(9, 15):
        px[x, 7] = cover + (255,)
        px[x, 13] = cover + (255,)
    for y in range(7, 14):
        px[9, y] = cover + (255,)
        px[14, y] = cover + (255,)
    px[9, 2] = px[14, 2] = cover + (255,)
    for x in range(10, 14):
        px[x, 4] = tuple(min(255, c + 30) for c in back.getpixel((4, 3))) + (255,)
    img.save(path)


def model3d(name):
    """Тонкая пластина 8×14×1: лицо (south) — левая половина текстуры, зад (north) — правая."""
    t = "rppda:item/" + name
    face = lambda uv: {"uv": uv, "texture": "#sheet"}
    return {
        "textures": {"sheet": t, "particle": t},
        "elements": [{
            "from": [4, 1, 7.5], "to": [12, 15, 8.5],
            "faces": {
                "south": face([0, 1, 8, 15]),
                "north": face([8, 1, 16, 15]),
                "east": face([7, 1, 8, 15]),
                "west": face([0, 1, 1, 15]),
                "up": face([0, 1, 8, 2]),
                "down": face([0, 14, 8, 15]),
            },
        }],
        "display": DISPLAY,
    }


def variant(model_dir, name, gui_texture):
    """Модель предмета: в руке, на земле, в рамке — объёмная; в инвентаре — плоская картинка."""
    with open(os.path.join(model_dir, name + "_3d.json"), "w", encoding="utf-8") as f:
        json.dump(model3d(name + "_3d"), f, indent=2)
    with open(os.path.join(model_dir, name + ".json"), "w", encoding="utf-8") as f:
        json.dump({
            "loader": "forge:separate_transforms",
            "base": {"parent": "rppda:item/" + name + "_3d"},
            "perspectives": {"gui": {"parent": "minecraft:item/generated", "textures": {"layer0": gui_texture}}},
        }, f, indent=2)


ACCENTS = [("blue", (79, 163, 255)), ("green", (79, 208, 122)), ("orange", (255, 154, 60)), ("violet", (167, 123, 255)), ("neon", (255, 79, 200))]


def modern_icon(accent, path):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y in range(1, 15):
        for x in range(4, 12):
            px[x, y] = (36, 40, 48, 255)
    for y in range(1, 15):
        px[4, y] = (58, 64, 74, 255)
        px[11, y] = (22, 24, 30, 255)
    for x in range(4, 12):
        px[x, 1] = (58, 64, 74, 255)
        px[x, 14] = (22, 24, 30, 255)
    for y in range(3, 12):
        for x in range(5, 11):
            px[x, y] = (14, 22, 33, 255)
    for x in range(6, 10):
        px[x, 5] = accent + (255,)
    px[6, 7] = px[7, 7] = (200, 210, 220, 255)
    px[6, 9] = px[7, 9] = px[8, 9] = (120, 130, 140, 255)
    px[7, 13] = px[8, 13] = accent + (255,)
    img.save(path)


def main():
    skin_dir = os.path.join(RES, "textures", "gui", "skin")
    item_dir = os.path.join(RES, "textures", "item")
    model_dir = os.path.join(RES, "models", "item")
    os.makedirs(skin_dir, exist_ok=True)
    swatches = Image.new("RGBA", (8 * 28, 3 * 20), (0, 0, 0, 0))
    overrides = []
    for n, (bid, paint, textured, (ox, oy)) in enumerate(BODIES):
        img = Image.new("RGBA", (W, H), (0, 0, 0, 255))
        paint(img)
        if textured:
            t = img.copy()
            chamfer(t)
            t.save(os.path.join(skin_dir, bid + ".png"))
        sw = img.crop((ox, oy, ox + 84, oy + 60)).resize((28, 20), Image.BOX)
        swatches.paste(sw, ((n % 8) * 28, (n // 8) * 20))
        item_icon(img, os.path.join(item_dir, "pda_" + bid + ".png"))
        if n == 0:
            item_icon(img, os.path.join(item_dir, "pda.png"))
        sheet(img, ((110, 240, 130), (24, 90, 44)), (40, 38, 36), os.path.join(item_dir, "pda_" + bid + "_3d.png"))
        variant(model_dir, "pda_" + bid, "rppda:item/pda_" + bid)
        overrides.append({"predicate": {"rppda:skin": round(n / 100, 2)}, "model": "rppda:item/pda_" + bid})
    # Современный КПК — тёмный, экран в цвет акцента (свойство 0,5 + номер акцента / 100).
    dark = Image.new("RGBA", (W, H), (36, 40, 48, 255))
    for i, (aid, col) in enumerate(ACCENTS):
        name = "pda_modern_" + aid
        modern_icon(col, os.path.join(item_dir, name + ".png"))
        sheet(dark, (col, (14, 22, 33)), (70, 76, 86), os.path.join(item_dir, name + "_3d.png"))
        variant(model_dir, name, "rppda:item/" + name)
        overrides.append({"predicate": {"rppda:skin": round(0.5 + i / 100, 2)}, "model": "rppda:item/" + name})
    # Системный КПК — красный корпус, янтарный экран.
    red = Image.new("RGBA", (W, H), (150, 48, 40, 255))
    sheet(red, ((255, 184, 64), (90, 46, 8)), (60, 24, 22), os.path.join(item_dir, "system_pda_3d.png"))
    variant(model_dir, "system_pda", "rppda:item/system_pda")
    swatches.save(os.path.join(RES, "textures", "gui", "skin_swatches.png"))
    with open(os.path.join(model_dir, "pda.json"), "w", encoding="utf-8") as f:
        json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": "rppda:item/pda"}, "overrides": overrides}, f, indent=2)
    print("bodies:", len(BODIES), "textured:", sum(1 for b in BODIES if b[2]))


if __name__ == "__main__":
    main()
