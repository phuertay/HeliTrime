#!/usr/bin/env python3
"""Distill Make Me a Hanzi medians into app/src/main/assets/rime/hanzi_lookup.bin.

The packing matches HanziStroke.packStroke: y grows downward.
graphics.txt is not shipped. The Arphic license is copied next to the table.
"""
import json
import math
import struct
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "assets" / "rime"
CACHE = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / ".cache" / "helitrime-hanzi"

GRAPHICS = "https://raw.githubusercontent.com/skishore/makemeahanzi/master/graphics.txt"
DICTIONARY = "https://raw.githubusercontent.com/skishore/makemeahanzi/master/dictionary.txt"
LICENSE = "https://raw.githubusercontent.com/skishore/makemeahanzi/master/APL/english/ARPHICPL.TXT"
ST = "https://raw.githubusercontent.com/BYVoid/OpenCC/master/data/dictionary/STCharacters.txt"
TS = "https://raw.githubusercontent.com/BYVoid/OpenCC/master/data/dictionary/TSCharacters.txt"


def fetch(url, name):
    CACHE.mkdir(parents=True, exist_ok=True)
    dest = CACHE / name
    if not dest.exists() or dest.stat().st_size == 0:
        print("fetch", name, flush=True)
        urllib.request.urlretrieve(url, dest)
    return dest


def pack(points, box):
    min_x, min_y, max_x, max_y = box
    width = max(max_x - min_x, 1.0)
    height = max(max_y - min_y, 1.0)
    x0, y0 = points[0]
    x1, y1 = points[-1]
    angle = math.atan2(y1 - y0, x1 - x0)
    if angle < 0:
        angle += math.tau
    direction = int((angle + math.pi / 8.0) / (math.pi / 4.0)) & 7
    length = 0.0
    for (ax, ay), (bx, by) in zip(points, points[1:]):
        length += math.hypot(bx - ax, by - ay)
    ratio = length / max(1.0, math.hypot(width, height))
    bucket = 0 if ratio < 0.25 else 1 if ratio < 0.5 else 2 if ratio < 0.75 else 3
    sx = min(0.999, max(0.0, (x0 - min_x) / width))
    sy = min(0.999, max(0.0, (y0 - min_y) / height))
    quadrant = (0 if sx < 0.5 else 1) + (0 if sy < 0.5 else 2)
    cx = sum(p[0] for p in points) / len(points)
    cy = sum(p[1] for p in points) / len(points)
    cell_x = min(2, max(0, int(((cx - min_x) / width) * 3)))
    cell_y = min(2, max(0, int(((cy - min_y) / height) * 3)))
    return direction | (bucket << 3) | (quadrant << 5) | ((cell_y * 3 + cell_x) << 7)


def load_maps(path):
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        left, _, right = line.partition("\t")
        if not left or not right:
            continue
        out[ord(left)] = [ord(c) for c in right.split(" ") if c]
    return out


def main():
    graphics = fetch(GRAPHICS, "graphics.txt")
    dictionary = fetch(DICTIONARY, "dictionary.txt")
    license_path = fetch(LICENSE, "APL-english")
    st = load_maps(fetch(ST, "STCharacters.txt"))
    ts = load_maps(fetch(TS, "TSCharacters.txt"))

    radicals = {}
    for line in dictionary.read_text(encoding="utf-8").splitlines():
        if not line:
            continue
        row = json.loads(line)
        radical = row.get("radical") or ""
        if radical:
            radicals[ord(row["character"])] = ord(radical[0])

    chars = []
    for line in graphics.read_text(encoding="utf-8").splitlines():
        if not line:
            continue
        row = json.loads(line)
        medians = row.get("medians") or []
        points = []
        strokes = []
        for median in medians:
            if len(median) < 2:
                continue
            stroke = [(float(p[0]), -float(p[1])) for p in median]
            strokes.append(stroke)
            points.extend(stroke)
        if not strokes:
            continue
        box = (
            min(p[0] for p in points),
            min(p[1] for p in points),
            max(p[0] for p in points),
            max(p[1] for p in points),
        )
        packed = [pack(stroke, box) for stroke in strokes]
        code = ord(row["character"])
        if code > 0xFFFF:
            continue
        chars.append((code, radicals.get(code, 0) if radicals.get(code, 0) <= 0xFFFF else 0, packed))
        if row["character"] in ("一", "丨"):
            print(row["character"], strokes[0][0], strokes[0][-1], "dir", packed[0] & 7, flush=True)
    chars.sort(key=lambda item: item[0])
    print("chars", len(chars), flush=True)

    blob = bytearray()
    blob += b"HZL1"
    blob += struct.pack("<HH", 1, len(chars))
    stroke_count = sum(len(item[2]) for item in chars)
    pairs = []
    for trad, simples in ts.items():
        if not simples or trad > 0xFFFF or simples[0] > 0xFFFF:
            continue
        pairs.append((trad, simples[0]))
    pairs.sort()
    many = []
    for simple, trads in st.items():
        trads = [trad for trad in trads if trad <= 0xFFFF]
        if simple > 0xFFFF or not trads or trads == [simple]:
            continue
        many.append((simple, trads))
    many.sort()
    blob += struct.pack("<IHH", stroke_count, len(pairs), len(many))
    offset = 0
    for code, radical, packed in chars:
        blob += struct.pack("<HHIH", code, radical, offset, len(packed))
        offset += len(packed)
    for _, _, packed in chars:
        for value in packed:
            blob += struct.pack("<H", value)
    for trad, simple in pairs:
        blob += struct.pack("<HH", trad, simple)
    for simple, trads in many:
        blob += struct.pack("<HB", simple, len(trads))
        for trad in trads:
            blob += struct.pack("<H", trad)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "hanzi_lookup.bin").write_bytes(blob)
    (OUT / "LICENSE-arphic.txt").write_bytes(license_path.read_bytes())
    print("wrote", len(blob), "bytes", flush=True)


if __name__ == "__main__":
    main()
