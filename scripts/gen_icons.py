"""Значки окна КПК, текстуры предметов и слота Curios.

Значки рисуются белыми на холсте 64×64 и сжимаются до 16×16 (в игре их красит код: обычный, наведённый,
выключенный). Порядок в ICONS = номер ячейки в атласе textures/gui/icons.png (8 в ряд) и номер в Icons.java.
Запуск: python scripts/gen_icons.py
"""
import math
import os

from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "rppda", "textures")
S = 64
W = 6  # толщина линий на холсте 64


def canvas():
    img = Image.new("L", (S, S), 0)
    return img, ImageDraw.Draw(img)


def person(d, cx, cy, r=9, body=16, fill=True):
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=255 if fill else None, outline=255, width=W)
    d.pieslice([cx - body - 2, cy + r + 3, cx + body + 2, cy + r + 3 + body * 2], 180, 360,
               fill=255 if fill else None, outline=255, width=W)


def i_chats(d):
    d.rounded_rectangle([6, 8, 58, 44], 10, outline=255, width=W)
    d.polygon([(16, 42), (16, 58), (32, 42)], fill=255)
    for x in (20, 32, 44):
        d.ellipse([x - 4, 22, x + 4, 30], fill=255)


def i_contacts(d):
    d.rounded_rectangle([8, 4, 52, 60], 6, outline=255, width=W)
    d.ellipse([22, 14, 38, 30], fill=255)
    d.pieslice([16, 32, 44, 60], 180, 360, fill=255)
    for y in (12, 26, 40):
        d.rectangle([50, y, 58, y + 6], fill=255)


def i_profile(d):
    d.rounded_rectangle([4, 12, 60, 52], 6, outline=255, width=W)
    d.ellipse([12, 20, 28, 36], fill=255)
    d.pieslice([8, 36, 32, 56], 180, 360, fill=255)
    d.rectangle([34, 22, 54, 27], fill=255)
    d.rectangle([34, 33, 54, 38], fill=255)
    d.rectangle([34, 44, 46, 46], fill=255)


def lock_body(d):
    d.rounded_rectangle([12, 28, 52, 58], 5, fill=255)
    d.rectangle([29, 38, 35, 48], fill=0)


def i_lock(d):
    lock_body(d)
    d.arc([18, 6, 46, 42], 180, 360, fill=255, width=W)
    d.line([(18, 24), (18, 30)], fill=255, width=W)
    d.line([(46, 24), (46, 30)], fill=255, width=W)


def i_unlock(d):
    lock_body(d)
    d.arc([30, 2, 58, 38], 180, 360, fill=255, width=W)
    d.line([(55, 20), (55, 26)], fill=255, width=W)


def i_close(d):
    d.line([(14, 14), (50, 50)], fill=255, width=W + 2)
    d.line([(50, 14), (14, 50)], fill=255, width=W + 2)


def i_back(d):
    d.line([(14, 32), (54, 32)], fill=255, width=W + 1)
    d.line([(14, 32), (32, 14)], fill=255, width=W + 1)
    d.line([(14, 32), (32, 50)], fill=255, width=W + 1)


def i_send(d):
    d.polygon([(6, 8), (60, 32), (6, 56), (14, 32)], fill=255)
    d.polygon([(14, 32), (34, 32), (12, 36)], fill=0)


def i_plus(d):
    d.line([(32, 10), (32, 54)], fill=255, width=W + 2)
    d.line([(10, 32), (54, 32)], fill=255, width=W + 2)


def i_group(d):
    person(d, 22, 20, 8, 14)
    d.ellipse([36, 10, 52, 26], fill=255)
    d.pieslice([32, 30, 60, 58], 180, 360, fill=255)
    d.rectangle([0, 46, 64, 64], fill=0)
    d.ellipse([14, 12, 30, 28], fill=255)
    d.pieslice([6, 32, 38, 62], 180, 360, fill=255)


def i_channel(d):
    d.polygon([(8, 24), (22, 24), (48, 8), (48, 56), (22, 40), (8, 40)], fill=255)
    d.line([(24, 40), (30, 56)], fill=255, width=W + 2)
    d.arc([44, 18, 62, 46], 300, 60, fill=255, width=W - 1)


def bell(d):
    d.pieslice([12, 6, 52, 50], 180, 360, fill=255)
    d.rectangle([12, 28, 52, 44], fill=255)
    d.polygon([(6, 48), (12, 40), (52, 40), (58, 48)], fill=255)
    d.ellipse([26, 48, 38, 60], fill=255)


def i_bell(d):
    bell(d)


def i_pda_only(d):
    d.rounded_rectangle([14, 4, 50, 60], 6, outline=255, width=W)
    d.rectangle([22, 12, 42, 40], fill=255)
    d.ellipse([28, 46, 36, 54], fill=255)


def i_mute(d):
    bell(d)
    d.line([(4, 4), (60, 60)], fill=0, width=W + 6)
    d.line([(6, 6), (58, 58)], fill=255, width=W)


def i_members(d):
    d.ellipse([6, 8, 20, 22], fill=255)
    d.rectangle([26, 12, 58, 18], fill=255)
    d.ellipse([6, 26, 20, 40], fill=255)
    d.rectangle([26, 30, 58, 36], fill=255)
    d.ellipse([6, 44, 20, 58], fill=255)
    d.rectangle([26, 48, 58, 54], fill=255)


def i_leave(d):
    d.line([(26, 8), (8, 8), (8, 56), (26, 56)], fill=255, width=W, joint="curve")
    d.line([(24, 32), (58, 32)], fill=255, width=W + 1)
    d.line([(58, 32), (44, 18)], fill=255, width=W + 1)
    d.line([(58, 32), (44, 46)], fill=255, width=W + 1)


def i_kick(d):
    person(d, 24, 18, 9, 16)
    d.line([(42, 46), (62, 46)], fill=0, width=W + 6)
    d.line([(42, 46), (62, 46)], fill=255, width=W)


def i_crown(d):
    d.polygon([(6, 18), (20, 34), (32, 12), (44, 34), (58, 18), (52, 50), (12, 50)], fill=255)
    d.rectangle([12, 52, 52, 58], fill=255)


def i_reply(d):
    d.line([(10, 28), (28, 10)], fill=255, width=W + 1)
    d.line([(10, 28), (28, 46)], fill=255, width=W + 1)
    d.arc([10, 28 - 26, 58, 28 + 26 + 4], 270, 90, fill=0, width=1)
    d.line([(12, 28), (40, 28)], fill=255, width=W + 1)
    d.arc([22, 28 - 3, 58, 60], 270, 0, fill=255, width=W + 1)


def i_trash(d):
    d.rectangle([8, 12, 56, 18], fill=255)
    d.rectangle([24, 4, 40, 12], outline=255, width=W - 2)
    d.polygon([(14, 22), (50, 22), (46, 60), (18, 60)], fill=255)
    for x in (25, 32, 39):
        d.line([(x, 28), (x, 54)], fill=0, width=3)


def i_copy(d):
    d.rounded_rectangle([20, 18, 56, 60], 4, outline=255, width=W)
    d.line([(10, 46), (10, 6), (42, 6)], fill=255, width=W)


def i_key(d):
    d.ellipse([4, 18, 30, 44], outline=255, width=W + 1)
    d.line([(28, 31), (60, 31)], fill=255, width=W + 1)
    d.line([(50, 31), (50, 44)], fill=255, width=W)
    d.line([(58, 31), (58, 42)], fill=255, width=W)


def speaker(d):
    d.polygon([(6, 22), (18, 22), (34, 8), (34, 56), (18, 42), (6, 42)], fill=255)


def i_silent(d):
    speaker(d)
    d.line([(42, 22), (58, 42)], fill=255, width=W)
    d.line([(58, 22), (42, 42)], fill=255, width=W)


def i_sound(d):
    speaker(d)
    d.arc([22, 18, 50, 46], 300, 60, fill=255, width=W - 1)
    d.arc([22, 6, 62, 58], 300, 60, fill=255, width=W - 1)


def i_accounts(d):
    d.ellipse([4, 6, 18, 20], fill=255)
    d.pieslice([2, 20, 20, 36], 180, 360, fill=255)
    d.rectangle([26, 10, 60, 16], fill=255)
    d.ellipse([4, 34, 18, 48], fill=255)
    d.pieslice([2, 48, 20, 64], 180, 360, fill=255)
    d.rectangle([26, 40, 60, 46], fill=255)


def i_log(d):
    d.rounded_rectangle([10, 4, 54, 60], 4, outline=255, width=W)
    for y in (16, 26, 36, 46):
        d.rectangle([18, y, 46 if y != 46 else 34, y + 4], fill=255)


def i_switch(d):
    d.line([(8, 20), (52, 20)], fill=255, width=W)
    d.polygon([(44, 8), (58, 20), (44, 32)], fill=255)
    d.line([(12, 44), (56, 44)], fill=255, width=W)
    d.polygon([(20, 32), (6, 44), (20, 56)], fill=255)


def i_give(d):
    d.rounded_rectangle([6, 6, 36, 58], 5, outline=255, width=W)
    d.rectangle([13, 13, 29, 36], fill=255)
    d.line([(50, 28), (50, 56)], fill=255, width=W + 1)
    d.line([(36, 42), (64, 42)], fill=255, width=W + 1)


def i_teleport(d):
    d.ellipse([10, 8, 54, 30], outline=255, width=W)
    d.ellipse([20, 14, 44, 24], fill=255)
    d.line([(32, 30), (32, 58)], fill=255, width=W + 1)
    d.polygon([(20, 46), (44, 46), (32, 60)], fill=255)


def i_edit(d):
    d.polygon([(42, 6), (58, 22), (22, 58), (6, 58), (6, 42)], fill=255)
    d.line([(36, 12), (52, 28)], fill=0, width=3)


def i_block(d):
    d.ellipse([6, 6, 58, 58], outline=255, width=W + 1)
    d.line([(16, 16), (48, 48)], fill=255, width=W + 1)


def i_check(d):
    d.line([(8, 34), (24, 50), (56, 14)], fill=255, width=W + 3, joint="curve")


def i_search(d):
    d.ellipse([6, 6, 40, 40], outline=255, width=W + 1)
    d.line([(36, 36), (58, 58)], fill=255, width=W + 3)


def i_message(d):
    d.rectangle([4, 14, 60, 50], outline=255, width=W)
    d.line([(4, 14), (32, 36), (60, 14)], fill=255, width=W)


def i_pda(d):
    d.rounded_rectangle([12, 4, 52, 60], 6, outline=255, width=W)
    d.rectangle([19, 11, 45, 38], fill=255)
    for x in (22, 32, 42):
        d.ellipse([x - 3, 45, x + 3, 51], fill=255)


def i_refresh(d):
    d.arc([8, 8, 56, 56], 30, 320, fill=255, width=W + 1)
    d.polygon([(46, 2), (58, 22), (38, 22)], fill=255)


def i_person_add(d):
    person(d, 24, 18, 9, 16)
    d.line([(50, 34), (50, 58)], fill=0, width=W + 8)
    d.line([(38, 46), (62, 46)], fill=0, width=W + 8)
    d.line([(50, 36), (50, 58)], fill=255, width=W)
    d.line([(40, 46), (60, 46)], fill=255, width=W)


def i_system(d):
    pts = []
    for k in range(10):
        r = 28 if k % 2 == 0 else 12
        a = -math.pi / 2 + k * math.pi / 5
        pts.append((32 + r * math.cos(a), 34 + r * math.sin(a)))
    d.polygon(pts, fill=255)


def i_globe(d):
    d.ellipse([6, 6, 58, 58], outline=255, width=W)
    d.ellipse([20, 6, 44, 58], outline=255, width=W - 1)
    d.line([(6, 32), (58, 32)], fill=255, width=W - 1)
    d.line([(10, 20), (54, 20)], fill=255, width=W - 2)
    d.line([(10, 44), (54, 44)], fill=255, width=W - 2)


def i_eye(d):
    d.ellipse([4, 16, 60, 48], outline=255, width=W)
    d.ellipse([22, 20, 42, 44], fill=255)


def i_brush(d):
    d.line([(52, 8), (26, 38)], fill=255, width=W + 2)
    d.polygon([(22, 34), (32, 44), (18, 58), (6, 58), (8, 46)], fill=255)


def bars(d):
    for i, h in enumerate((14, 26, 38, 50)):
        x = 8 + i * 13
        d.rectangle([x, 58 - h, x + 8, 58], fill=255)


def i_signal(d):
    bars(d)


def i_no_signal(d):
    bars(d)
    d.line([(4, 6), (60, 60)], fill=0, width=W + 6)
    d.line([(6, 8), (58, 58)], fill=255, width=W)


def i_mask(d):
    d.chord([6, 6, 58, 62], 180, 360, fill=255)
    d.rectangle([6, 32, 58, 40], fill=255)
    d.pieslice([6, 18, 58, 62], 0, 180, fill=255)
    d.ellipse([16, 22, 28, 32], fill=0)
    d.ellipse([36, 22, 48, 32], fill=0)
    d.arc([20, 38, 44, 54], 20, 160, fill=0, width=W - 1)


ICONS = [i_chats, i_contacts, i_profile, i_lock, i_unlock, i_close, i_back, i_send,
         i_plus, i_group, i_channel, i_bell, i_pda_only, i_mute, i_members, i_leave,
         i_kick, i_crown, i_reply, i_trash, i_copy, i_key, i_silent, i_sound,
         i_accounts, i_log, i_switch, i_give, i_teleport, i_edit, i_block, i_check,
         i_search, i_message, i_pda, i_refresh, i_person_add, i_system, i_globe, i_eye,
         i_brush, i_signal, i_no_signal, i_mask]


def atlas():
    cols = 8
    rows = (len(ICONS) + cols - 1) // cols
    out = Image.new("RGBA", (cols * 16, 64 if rows <= 4 else rows * 16), (0, 0, 0, 0))
    for n, fn in enumerate(ICONS):
        img, d = canvas()
        fn(d)
        small = img.resize((16, 16), Image.LANCZOS)
        cell = Image.new("RGBA", (16, 16), (255, 255, 255, 0))
        cell.putalpha(small)
        out.paste(cell, ((n % cols) * 16, (n // cols) * 16))
    path = os.path.join(ROOT, "gui", "icons.png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    out.save(path)
    print("icons:", len(ICONS), "->", path)


def device(accent, screen, path, body=(46, 50, 58)):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    dark = tuple(max(0, c - 18) for c in body)
    light = tuple(min(255, c + 28) for c in body)
    for y in range(1, 15):
        for x in range(3, 13):
            px[x, y] = body + (255,)
    for y in range(1, 15):
        px[3, y] = light + (255,)
        px[12, y] = dark + (255,)
    for x in range(3, 13):
        px[x, 1] = light + (255,)
        px[x, 14] = dark + (255,)
    for y in range(3, 10):
        for x in range(5, 11):
            px[x, y] = screen + (255,)
    px[5, 3] = tuple(min(255, c + 60) for c in screen) + (255,)
    px[6, 3] = tuple(min(255, c + 40) for c in screen) + (255,)
    for x in (5, 7, 9):
        px[x, 11] = accent + (255,)
        px[x + 1, 11] = accent + (255,)
    px[7, 13] = accent + (255,)
    px[8, 13] = accent + (255,)
    px[12, 2] = (20, 22, 26, 255)
    px[13, 2] = (20, 22, 26, 255)
    px[13, 1] = (20, 22, 26, 255)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def slot_icon(path):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    c = (120, 120, 120, 120)
    for y in range(2, 14):
        px[4, y] = c
        px[11, y] = c
    for x in range(4, 12):
        px[x, 2] = c
        px[x, 13] = c
    for y in range(4, 9):
        for x in range(6, 10):
            px[x, y] = (110, 110, 110, 90)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


if __name__ == "__main__":
    atlas()
    # Обычный КПК (и корпуса) — scripts/gen_skins.py.
    device((230, 70, 60), (170, 40, 40), os.path.join(ROOT, "item", "system_pda.png"), body=(40, 36, 36))
    slot_icon(os.path.join(ROOT, "slot", "pda.png"))
    print("items, slot: ok")
