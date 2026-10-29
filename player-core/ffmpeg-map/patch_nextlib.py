#!/usr/bin/env python3
"""Unpack nextlib-media3ext into a classes jar and jni/ for player-core.

FfmpegLibrary is compiled from player-core sources, so it is stripped here.
That keeps a single class in the AAR and the APK.
"""
import shutil
import sys
import zipfile
from pathlib import Path

STRIP = {
    "io/github/anilbeesetti/nextlib/media3ext/ffdecoder/FfmpegLibrary.class",
    "io/github/anilbeesetti/nextlib/media3ext/ffdecoder/FfmpegLibrary$1.class",
}


def main() -> None:
    aar = Path(sys.argv[1])
    out_dir = Path(sys.argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)
    jar_path = out_dir / "nextlib-classes.jar"
    jni_out = out_dir / "jni"
    if jni_out.exists():
        shutil.rmtree(jni_out)
    jni_out.mkdir()
    with zipfile.ZipFile(aar) as src, zipfile.ZipFile(jar_path, "w", compression=zipfile.ZIP_DEFLATED) as dst:
        classes = zipfile.ZipFile(src.open("classes.jar"))
        for info in classes.infolist():
            if info.filename in STRIP or info.is_dir():
                continue
            dst.writestr(info, classes.read(info.filename))
        for info in src.infolist():
            if not info.filename.startswith("jni/") or info.is_dir():
                continue
            target = jni_out / info.filename[len("jni/") :]
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(src.read(info.filename))
    # The class we compile ourselves must not still be in the jar.
    with zipfile.ZipFile(jar_path) as check:
        left = [n for n in check.namelist() if n.startswith("io/github/anilbeesetti/nextlib/media3ext/ffdecoder/FfmpegLibrary")]
    if left:
        raise SystemExit(f"FfmpegLibrary still in jar: {left}")


if __name__ == "__main__":
    main()
