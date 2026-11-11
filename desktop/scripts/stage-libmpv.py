#!/usr/bin/env python3
"""Складывает libmpv и нужные ей библиотеки в desktop/natives/<os>-<arch>.

Пользователю не нужно ставить mpv или VLC: упаковка забирает этот каталог.
Графический стек (OpenGL, Vulkan, X11, Wayland, PulseAudio, ALSA) остаётся
системным: эти библиотеки привязаны к драйверу и рабочему столу.
"""

from __future__ import annotations

import os
import platform
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FORCE = "--force" in sys.argv

# Библиотеки рабочего стола и libc. Их не копируем: версия должна совпасть с ОС.
SYSTEM_PREFIXES = (
    "libc.so", "libm.so", "libdl.so", "libpthread.so", "librt.so", "libresolv.so",
    "libstdc++.so", "libgcc_s.so", "ld-linux",
    "libx11.so", "libxext.so", "libxrandr.so", "libxss.so", "libxv.so", "libxpresent.so",
    "libxfixes.so", "libxcursor.so", "libxrender.so", "libxi.so", "libxinerama.so",
    "libxkbcommon.so", "libxcb.so", "libxau.so", "libxdmcp.so",
    "libwayland-", "libegl.so", "libgl.so", "libgldispatch.so", "libglx.so",
    "libvulkan.so", "libdrm.so", "libgbm.so",
    "libva.so", "libva-drm.so", "libva-x11.so", "libva-wayland.so", "libvdpau.so",
    "libasound.so", "libpulse.so", "libpulsecommon", "libpipewire", "libjack.so",
    "libz.so", "libjpeg.so", "libpng", "libexpat.so", "libffi.so", "libpcre",
    "libbsd.so", "libmd.so", "libunwind.so", "libelf.so", "libdw.so",
    "libdbus", "libsystemd.so", "libcap.so", "libgcrypt.so", "libgpg-error.so",
    "liblzma.so", "libbz2.so", "libbrotli", "libzstd.so",
    "libfreetype.so", "libfontconfig.so", "libharfbuzz.so", "libfribidi.so",
    "libgraphite", "libpng16.so", "libselinux.so", "libpcre2", "libmount.so",
    "libblkid.so", "libuuid.so", "libnsl.so", "libtirpc.so", "libgssapi",
    "libkrb5", "libk5crypto", "libcom_err", "libkeyutils", "libunistring",
    "libidn2", "libhogweed", "libnettle", "libtasn1", "libp11", "libgnutls",
)


def system_lib(name: str) -> bool:
    low = name.lower()
    return any(low.startswith(p) for p in SYSTEM_PREFIXES)


def run(cmd: list[str], cwd: Path | None = None) -> str:
    print("+", " ".join(cmd), flush=True)
    out = subprocess.check_output(cmd, cwd=cwd, text=True, stderr=subprocess.STDOUT)
    return out


def linux_dest() -> Path:
    dest = ROOT / "natives" / "linux-x64"
    marker = dest / "libmpv.so.2"
    if marker.exists() and not FORCE:
        print(f"libmpv already staged: {dest}")
        return dest
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True)
    so = Path("/lib/x86_64-linux-gnu/libmpv.so.2")
    if not so.exists():
        listed = subprocess.check_output(["ldconfig", "-p"], text=True, stderr=subprocess.DEVNULL)
        for line in listed.splitlines():
            if "libmpv.so.2" in line and "=>" in line:
                so = Path(line.split("=>", 1)[1].strip().split()[0])
                break
    if not so.exists():
        sys.exit("libmpv.so.2 not found. Install the libmpv2 package (apt install libmpv-dev) and rerun.")
    copied: set[str] = set()

    def consider(path: Path) -> None:
        if not path.exists():
            return
        name = path.name
        if name in copied or system_lib(name):
            return
        copied.add(name)
        target = dest / name
        shutil.copy2(os.path.realpath(path), target)
        try:
            text = subprocess.check_output(["ldd", str(target)], text=True, stderr=subprocess.DEVNULL)
        except subprocess.CalledProcessError:
            return
        for line in text.splitlines():
            if "=>" not in line:
                continue
            right = line.split("=>", 1)[1].strip().split()[0]
            if right.startswith("/"):
                consider(Path(right))

    consider(so)
    (dest / "libmpv.so").symlink_to("libmpv.so.2")
    for lib in dest.iterdir():
        if lib.is_symlink() or not lib.is_file():
            continue
        subprocess.check_call(["patchelf", "--set-rpath", "$ORIGIN", str(lib)])
    print(f"staged {len(list(dest.iterdir()))} files in {dest}")
    return dest


def macos_dest() -> Path:
    machine = platform.machine().lower()
    folder = "macos-arm64" if machine in ("arm64", "aarch64") else "macos-x64"
    dest = ROOT / "natives" / folder
    marker = dest / "libmpv.dylib"
    if marker.exists() and not FORCE:
        print(f"libmpv already staged: {dest}")
        return dest
    brew = shutil.which("brew")
    if brew is None:
        sys.exit("Homebrew is required to bundle libmpv on macOS.")
    if subprocess.call([brew, "list", "mpv"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) != 0:
        subprocess.check_call([brew, "install", "mpv"])
    prefix = subprocess.check_output([brew, "--prefix"], text=True).strip()
    candidates = list(Path(prefix).joinpath("lib").glob("libmpv*.dylib"))
    cellar = Path(prefix).joinpath("opt/mpv/lib")
    candidates += list(cellar.glob("libmpv*.dylib"))
    src = next((p for p in candidates if p.name.startswith("libmpv.2") or p.name == "libmpv.dylib"), None)
    if src is None:
        sys.exit("libmpv.dylib not found after brew install mpv")
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True)
    copied: dict[str, Path] = {}

    def resolve(ref: str, owner: Path) -> Path | None:
        if ref.startswith("/"):
            return Path(ref) if Path(ref).exists() else None
        name = ref.split("/")[-1]
        for base in (owner.parent, Path(prefix) / "lib", Path("/usr/local/lib")):
            hit = base / name
            if hit.exists():
                return hit
        return None

    def consider(path: Path) -> None:
        name = path.name
        if name in copied:
            return
        if str(path).startswith("/usr/lib") or str(path).startswith("/System"):
            return
        copied[name] = path
        try:
            text = subprocess.check_output(["otool", "-L", str(path)], text=True)
        except subprocess.CalledProcessError:
            return
        for line in text.splitlines()[1:]:
            ref = line.strip().split(" (")[0]
            found = resolve(ref, path)
            if found is not None:
                consider(found)

    consider(src.resolve())
    for name, path in copied.items():
        shutil.copy2(os.path.realpath(path), dest / name)
    if not (dest / "libmpv.dylib").exists():
        link_from = next(p.name for p in dest.glob("libmpv*.dylib"))
        (dest / "libmpv.dylib").symlink_to(link_from)
    for lib in list(dest.glob("*.dylib")):
        if lib.is_symlink():
            continue
        subprocess.check_call(["install_name_tool", "-id", f"@rpath/{lib.name}", str(lib)])
        text = subprocess.check_output(["otool", "-L", str(lib)], text=True)
        for line in text.splitlines()[1:]:
            ref = line.strip().split(" (")[0]
            base = ref.split("/")[-1]
            if (dest / base).exists() and ref != f"@rpath/{base}":
                subprocess.check_call(["install_name_tool", "-change", ref, f"@rpath/{base}", str(lib)])
        subprocess.check_call(["install_name_tool", "-add_rpath", "@loader_path", str(lib)])
        subprocess.call(["codesign", "--force", "--sign", "-", str(lib)], stdout=subprocess.DEVNULL)
    print(f"staged {len(list(dest.iterdir()))} files in {dest}")
    return dest


WIN_DEV = "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20261110/mpv-dev-x86_64-20261110-git-a61f6c3abd.7z"
WIN_BIN = "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20261110/mpv-x86_64-20261110-git-a61f6c3abd.7z"


def seven_zip() -> str:
    candidates = ["7z", "7za", r"C:\Program Files\7-Zip\7z.exe", r"C:\Program Files (x86)\7-Zip\7z.exe"]
    for name in candidates:
        if shutil.which(name) or Path(name).exists():
            return name
    sys.exit("7-Zip is required to unpack the Windows libmpv build.")


def windows_dest() -> Path:
    dest = ROOT / "natives" / "windows-x64"
    marker = dest / "libmpv-2.dll"
    if marker.exists() and not FORCE:
        print(f"libmpv already staged: {dest}")
        return dest
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True)
    cache = ROOT / "build" / "mpv-win"
    cache.mkdir(parents=True, exist_ok=True)
    z7 = seven_zip()
    for url in (WIN_BIN, WIN_DEV):
        archive = cache / url.rsplit("/", 1)[1]
        if not archive.exists() or FORCE:
            print(f"download {url}", flush=True)
            urllib.request.urlretrieve(url, archive)
        subprocess.check_call([z7, "x", "-y", f"-o{dest}", str(archive)])
    dlls = list(dest.rglob("*.dll"))
    for dll in dlls:
        if dll.parent != dest:
            target = dest / dll.name
            if not target.exists():
                shutil.copy2(dll, target)
    for child in list(dest.iterdir()):
        if child.is_dir():
            shutil.rmtree(child)
    if not (dest / "libmpv-2.dll").exists():
        sys.exit(f"libmpv-2.dll missing after unpack. Contents: {[p.name for p in dest.iterdir()]}")
    print(f"staged {len(list(dest.iterdir()))} files in {dest}")
    return dest


def main() -> None:
    system = platform.system().lower()
    if system == "linux":
        linux_dest()
    elif system == "darwin":
        macos_dest()
    elif system == "windows":
        windows_dest()
    else:
        sys.exit(f"unsupported OS: {system}")


if __name__ == "__main__":
    main()
