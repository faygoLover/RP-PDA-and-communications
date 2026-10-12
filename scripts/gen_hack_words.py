"""Слова для взлома пароля (как терминал Fallout): английские, пулы похожих слов одной длины.

Источник — английский перевод Minecraft (en_us.json из client-extra.jar в кеше ForgeGradle): слова, которые там
встречаются строчными, длиной 5–10 букв. Для каждой длины — 10 пулов: зерно + самые похожие на него слова
(общие буквы на тех же местах) + несколько непохожих, чтобы «совпадения» были разными.
Запуск: python scripts/gen_hack_words.py  →  src/main/resources/data/rppda/pda/hack_words.json
"""
import collections
import json
import os
import random
import re
import zipfile

JAR = os.path.expanduser("~/.gradle/caches/forge_gradle/minecraft_repo/versions/1.20.1/client-extra.jar")
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data", "rppda", "pda", "hack_words.json")
POOLS, SIMILAR, OTHERS = 10, 13, 6
SKIP = {"abuse", "abusive", "terrorism", "suicide", "drugs", "sexual", "harassment", "extremism", "violent",
        "exploitation", "imagery", "intimate", "consensual", "bullying", "hatred", "behavior", "behaviour",
        "actionbar", "beaconator", "minecraft", "mojang", "realms", "multiplayer", "singleplayer", "gamerule",
        "gamemode", "tooltip", "tooltips", "json", "texture", "textures", "unicode", "spawnpoint", "chunks"}


def like(a, b):
    return sum(x == y for x, y in zip(a, b))


def main():
    z = zipfile.ZipFile(JAR)
    d = json.loads(z.read("assets/minecraft/lang/en_us.json"))
    lower = collections.Counter()
    for v in d.values():
        for w in re.findall(r"[A-Za-z]+", v):
            if w.islower():
                lower[w] += 1
    rnd = random.Random(1987)
    out = {}
    for L in range(5, 11):
        words = sorted(w for w in lower if len(w) == L and w not in SKIP)
        used = set()
        pools = []
        for _ in range(POOLS):
            free = [w for w in words if w not in used]
            if len(free) < SIMILAR + OTHERS + 1:
                free = list(words)  # длинных слов мало — пулы могут пересекаться
            # Зерно — слово, у которого больше всего похожих среди свободных.
            seed = max(rnd.sample(free, min(60, len(free))), key=lambda s: sum(like(s, w) >= 2 for w in free))
            near = sorted((w for w in free if w != seed), key=lambda w: -like(seed, w))[:SIMILAR]
            rest = [w for w in free if w != seed and w not in near and like(seed, w) <= 1]
            far = rnd.sample(rest, min(OTHERS, len(rest)))
            pool = sorted({seed, *near, *far})
            used.update(pool)
            pools.append([w.upper() for w in pool])
        out[str(L)] = pools
        print(L, len(words), [len(p) for p in pools], pools[0][:8])
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1)
    print("->", OUT)


if __name__ == "__main__":
    main()
