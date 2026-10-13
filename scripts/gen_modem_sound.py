"""Тихий «модем» для передачи файла между КПК (петля ~2 с): FSK-тоны 300 бод, как у старых модемов, и лёгкий шум.

Запуск: python scripts/gen_modem_sound.py → src/main/resources/assets/rppda/sounds/pda/modem.ogg
"""
import os

import numpy as np
import soundfile as sf

RATE = 22050
SECONDS = 2.0
BAUD = 300
rng = np.random.default_rng(14)

n = int(RATE * SECONDS)
t = np.arange(n) / RATE
bits = rng.integers(0, 2, int(SECONDS * BAUD) + 1)
bit = bits[(t * BAUD).astype(int)]
# Две пары частот (как у двух сторон связи), фаза непрерывная — без щелчков на смене бита.
f1 = np.where(bit == 1, 1270.0, 1070.0)
f2 = np.where(np.roll(bit, 37) == 1, 2225.0, 2025.0)
ph1 = 2 * np.pi * np.cumsum(f1) / RATE
ph2 = 2 * np.pi * np.cumsum(f2) / RATE
sig = 0.5 * np.sin(ph1) + 0.35 * np.sin(ph2)
sig += 0.08 * rng.standard_normal(n)
# Петля: края плавно сходятся.
fade = int(RATE * 0.03)
env = np.ones(n)
env[:fade] = np.linspace(0, 1, fade)
env[-fade:] = np.linspace(1, 0, fade)
sig = sig * env
sig = 0.35 * sig / np.max(np.abs(sig))

out = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "rppda", "sounds", "pda", "modem.ogg")
sf.write(out, sig.astype(np.float32), RATE, format="OGG", subtype="VORBIS")
print("ok", os.path.abspath(out))
