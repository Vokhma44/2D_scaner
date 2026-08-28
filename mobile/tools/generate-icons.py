#!/usr/bin/env python3
"""Генератор иконок мобильного клиента.

PNG нужен для установки на домашний экран: Android берёт иконки 192 и 512,
iOS — apple-touch-icon 180. Иконка рисуется кодом, чтобы её можно было
перегенерировать без графического редактора и без внешних зависимостей.

Запуск: python3 mobile/tools/generate-icons.py
"""

from __future__ import annotations

import struct
import zlib
from pathlib import Path

BACKGROUND = (17, 19, 24, 255)
FOREGROUND = (255, 255, 255, 255)
ACCENT = (88, 166, 255, 255)

PUBLIC_DIR = Path(__file__).resolve().parent.parent / "public"


def write_png(path: Path, pixels: list[list[tuple[int, int, int, int]]]) -> None:
    height = len(pixels)
    width = len(pixels[0])

    raw = bytearray()
    for row in pixels:
        raw.append(0)  # фильтр строки: None
        for r, g, b, a in row:
            raw += bytes((r, g, b, a))

    def chunk(tag: bytes, payload: bytes) -> bytes:
        return (
            struct.pack(">I", len(payload))
            + tag
            + payload
            + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)
        )

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )
    path.write_bytes(png)


def rounded_background(size: int) -> list[list[tuple[int, int, int, int]]]:
    radius = size * 0.22
    canvas = []
    for y in range(size):
        row = []
        for x in range(size):
            if inside_rounded_rect(x, y, 0, 0, size, size, radius):
                row.append(BACKGROUND)
            else:
                row.append((0, 0, 0, 0))
        canvas.append(row)
    return canvas


def inside_rounded_rect(x: float, y: float, left: float, top: float,
                        width: float, height: float, radius: float) -> bool:
    right, bottom = left + width, top + height
    cx = min(max(x, left + radius), right - radius)
    cy = min(max(y, top + radius), bottom - radius)
    return (x - cx) ** 2 + (y - cy) ** 2 <= radius ** 2 or (
        left + radius <= x <= right - radius and top <= y <= bottom
    ) or (
        left <= x <= right and top + radius <= y <= bottom - radius
    )


def fill_rect(canvas, x0: float, y0: float, x1: float, y1: float, color) -> None:
    size = len(canvas)
    for y in range(max(0, int(y0)), min(size, int(y1))):
        for x in range(max(0, int(x0)), min(size, int(x1))):
            canvas[y][x] = color


def draw_icon(size: int) -> list[list[tuple[int, int, int, int]]]:
    canvas = rounded_background(size)
    unit = size / 24

    # Уголки видоискателя: узнаваемый силуэт сканера штрихкодов.
    arm, thickness, margin = unit * 5, unit * 1.4, unit * 4
    far = size - margin
    for x0, y0, horizontal in (
        (margin, margin, True), (margin, margin, False),
        (far - arm, margin, True), (far - thickness, margin, False),
        (margin, far - thickness, True), (margin, far - arm, False),
        (far - arm, far - thickness, True), (far - thickness, far - arm, False),
    ):
        if horizontal:
            fill_rect(canvas, x0, y0, x0 + arm, y0 + thickness, FOREGROUND)
        else:
            fill_rect(canvas, x0, y0, x0 + thickness, y0 + arm, FOREGROUND)

    # Модули кода внутри рамки.
    cell = unit * 1.7
    origin = size / 2 - cell * 2.5
    pattern = [
        "11011",
        "10110",
        "01101",
        "11010",
        "01011",
    ]
    for row, line in enumerate(pattern):
        for col, bit in enumerate(line):
            if bit == "1":
                x = origin + col * cell
                y = origin + row * cell
                fill_rect(canvas, x, y, x + cell * 0.8, y + cell * 0.8, FOREGROUND)

    # Луч сканера.
    fill_rect(canvas, margin, size / 2 - unit * 0.3, size - margin, size / 2 + unit * 0.3, ACCENT)
    return canvas


def main() -> None:
    PUBLIC_DIR.mkdir(parents=True, exist_ok=True)
    for size, name in ((192, "icon-192.png"), (512, "icon-512.png"), (180, "apple-touch-icon.png")):
        write_png(PUBLIC_DIR / name, draw_icon(size))
        print(f"создано {name} ({size}x{size})")


if __name__ == "__main__":
    main()
