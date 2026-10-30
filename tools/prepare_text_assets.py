#!/usr/bin/env python3
"""Creates assets/icon_text.png (standard library only). Run before Metro reads index.js."""
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SIZE = 256
SEGMENTS = [  # x1, y1, x2, y2, stroke width: frame, letter T, two text lines
    (40, 30, 216, 30, 12), (216, 30, 216, 226, 12), (216, 226, 40, 226, 12), (40, 226, 40, 30, 12),
    (76, 78, 180, 78, 18), (128, 78, 128, 150, 18),
    (76, 184, 180, 184, 10), (76, 206, 150, 206, 10),
]

def near(px, py, seg):
    x1, y1, x2, y2, w = seg
    dx, dy = x2 - x1, y2 - y1
    t = max(0.0, min(1.0, ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)))
    return (px - x1 - t * dx) ** 2 + (py - y1 - t * dy) ** 2 <= (w / 2) ** 2

def make_png():
    rows = bytearray()
    for y in range(SIZE):
        rows.append(0)
        byte = 0
        for x in range(SIZE):
            black = any(near(x + 0.5, y + 0.5, s) for s in SEGMENTS)
            byte = (byte << 1) | (0 if black else 1)
            if x % 8 == 7:
                rows.append(byte)
                byte = 0

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", SIZE, SIZE, 1, 0, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b""))

def main():
    (ROOT / "assets").mkdir(exist_ok=True)
    (ROOT / "assets/icon_text.png").write_bytes(make_png())
    print("Text toolbar icon created.")

if __name__ == "__main__":
    main()
