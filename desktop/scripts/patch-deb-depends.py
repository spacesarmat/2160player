#!/usr/bin/env python3
"""Дописывает в control пакеты рабочего стола, без которых не грузится libmpv.

Сам mpv в список не входит: кодеки уже лежат рядом с приложением.
"""

from __future__ import annotations

import sys
from pathlib import Path

EXTRA = (
    "libasound2t64 | libasound2, libpulse0, libx11-6, libegl1, libvulkan1, "
    "libva2, libvdpau1, libdrm2, libgbm1, libwayland-client0, libxrandr2, "
    "libxext6, libxss1, libxv1, libxpresent1, libxkbcommon0, "
    "libjack-jackd2-0 | libjack0, libpipewire-0.3-0"
)


def main() -> None:
    path = Path(sys.argv[1])
    text = path.read_text()
    lines = []
    found = False
    for line in text.splitlines():
        if line.startswith("Depends:"):
            found = True
            current = line.split(":", 1)[1].strip()
            line = "Depends: " + ", ".join(part for part in (current, EXTRA) if part)
        lines.append(line)
    if not found:
        lines.append("Depends: " + EXTRA)
    path.write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
