#!/usr/bin/env python3
"""Distill Make Me a Hanzi medians into app/src/main/assets/rime/hanzi_lookup.bin.

The RDG1 trailer is one toned syllable per character, the first pinyin listing.

The packing matches HanziStroke.packStroke: y grows downward, and a stroke is
placed in the character square rather than in its own bounding box.
A bend is the head and the tail disagreeing by 90 degrees or more.
FRQ1 is a log-scaled count from essay.txt, 255 for the most common character.
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


# graphics.txt x is 0..1024. y grows up, about -96..885. The pad is 0..1000, y down.
CANVAS_X = 1024.0
CANVAS_TOP = 928.0
CANVAS_SPAN = 1024.0
SQUARE = 1000.0


def direction(dx, dy):
    if dx == 0 and dy == 0:
        return 0
    angle = math.atan2(dy, dx)
    if angle < 0:
        angle += math.tau
    return int((angle + math.pi / 8.0) / (math.pi / 4.0)) & 7


def direction_distance(left, right):
    delta = abs(left - right)
    return min(delta, 8 - delta)


def bend_bits(points):
    count = len(points)
    if count < 3:
        return 0
    head_at = max(1, count // 3)
    tail_at = min(count - 2, (2 * count) // 3)
    head = direction(points[head_at][0] - points[0][0], points[head_at][1] - points[0][1])
    tail = direction(points[-1][0] - points[tail_at][0], points[-1][1] - points[tail_at][1])
    if direction_distance(head, tail) < 2:
        return 0
    return (tail << 11) | (1 << 14)


def to_pad(x, y_up):
    px = min(SQUARE, max(0.0, x / CANVAS_X * SQUARE))
    py = min(SQUARE, max(0.0, (CANVAS_TOP - y_up) / CANVAS_SPAN * SQUARE))
    return px, py


def pack(points):
    min_x, min_y, max_x, max_y = 0.0, 0.0, SQUARE, SQUARE
    width = max(max_x - min_x, 1.0)
    height = max(max_y - min_y, 1.0)
    x0, y0 = points[0]
    x1, y1 = points[-1]
    direction_bits = direction(x1 - x0, y1 - y0)
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
    return (
        direction_bits
        | (bucket << 3)
        | (quadrant << 5)
        | ((cell_y * 3 + cell_x) << 7)
        | bend_bits(points)
    )


def first_syllable(pinyin):
    if not isinstance(pinyin, list) or not pinyin:
        return ""
    syllable = pinyin[0]
    if not isinstance(syllable, str):
        return ""
    return syllable.strip()


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


def load_frequency(path):
    counts = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        word, sep, count = line.partition("\t")
        if not sep:
            continue
        try:
            number = int(count)
        except ValueError:
            continue
        if number <= 0:
            continue
        for char in word:
            code = ord(char)
            counts[code] = counts.get(code, 0) + number
    return counts


def main():
    graphics = fetch(GRAPHICS, "graphics.txt")
    dictionary = fetch(DICTIONARY, "dictionary.txt")
    license_path = fetch(LICENSE, "APL-english")
    st = load_maps(fetch(ST, "STCharacters.txt"))
    ts = load_maps(fetch(TS, "TSCharacters.txt"))
    frequency = load_frequency(OUT / "essay.txt")

    radicals = {}
    # First listing only. 行 is xíng here, and 了 is the neutral le.
    readings = {}
    for line in dictionary.read_text(encoding="utf-8").splitlines():
        if not line:
            continue
        row = json.loads(line)
        code = ord(row["character"])
        radical = row.get("radical") or ""
        if radical:
            radicals[code] = ord(radical[0])
        syllable = first_syllable(row.get("pinyin"))
        if syllable:
            readings[code] = syllable

    chars = []
    for line in graphics.read_text(encoding="utf-8").splitlines():
        if not line:
            continue
        row = json.loads(line)
        medians = row.get("medians") or []
        strokes = []
        for median in medians:
            if len(median) < 2:
                continue
            strokes.append([to_pad(float(p[0]), float(p[1])) for p in median])
        if not strokes:
            continue
        packed = [pack(stroke) for stroke in strokes]
        code = ord(row["character"])
        if code > 0xFFFF:
            continue
        radical = radicals.get(code, 0)
        if radical > 0xFFFF:
            radical = 0
        chars.append((code, radical, packed, readings.get(code, "")))
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
    for code, radical, packed, _reading in chars:
        blob += struct.pack("<HHIH", code, radical, offset, len(packed))
        offset += len(packed)
    for _, _, packed, _reading in chars:
        for value in packed:
            blob += struct.pack("<H", value)
    for trad, simple in pairs:
        blob += struct.pack("<HH", trad, simple)
    for simple, trads in many:
        blob += struct.pack("<HB", simple, len(trads))
        for trad in trads:
            blob += struct.pack("<H", trad)
    blob += b"RDG1"
    blob += struct.pack("<H", len(chars))
    for _, _, _, reading in chars:
        raw = reading.encode("utf-8")
        blob += struct.pack("<B", len(raw))
        blob += raw
    hi = max((frequency.get(code, 0) for code, _, _, _ in chars), default=1) or 1
    blob += b"FRQ1"
    blob += struct.pack("<H", len(chars))
    for code, _, _, _ in chars:
        count = frequency.get(code, 0)
        if count <= 0:
            bucket = 0
        else:
            bucket = min(255, int(round(math.log1p(count) / math.log1p(hi) * 255)))
        blob += struct.pack("<B", bucket)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "hanzi_lookup.bin").write_bytes(blob)
    (OUT / "LICENSE-arphic.txt").write_bytes(license_path.read_bytes())
    print("wrote", len(blob), "bytes", flush=True)


if __name__ == "__main__":
    main()
