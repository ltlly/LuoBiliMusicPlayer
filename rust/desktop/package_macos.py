"""Package a macOS player and its matching MediaMP audio runtime as a Finder app."""
import argparse
import plistlib
import shutil
import struct
import tempfile
import tomllib
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTENTS = "BiliMusicPlayer.app/Contents"


def binary_info(stream):
    magic, cpu = struct.unpack("<II", stream.read(8))
    if magic != 0xFEEDFACF or cpu not in (0x0100000C, 0x01000007):
        raise ValueError("Expected a 64-bit Intel or Apple Silicon Mach-O file")
    # Each thin Mach-O declares its minimum macOS in its load commands.
    _, _, commands, _, _, _ = struct.unpack("<6I", stream.read(24))
    minimum = 0
    for _ in range(commands):
        command, size = struct.unpack("<II", stream.read(8))
        if size < 8:
            raise ValueError("Invalid Mach-O load command")
        data = stream.read(size - 8)
        if command == 0x32:  # LC_BUILD_VERSION
            platform, version = struct.unpack_from("<II", data)
            if platform != 1:
                raise ValueError("Expected a macOS build")
            minimum = max(minimum, version)
        elif command == 0x24:  # LC_VERSION_MIN_MACOSX
            minimum = max(minimum, struct.unpack_from("<I", data)[0])
    return cpu, minimum


def build(exe, runtime, output):
    with exe.open("rb") as binary:
        cpu, minimum = binary_info(binary)
    with (ROOT / "Cargo.toml").open("rb") as manifest:
        version = tomllib.load(manifest)["package"]["version"]
    plist = {
        "CFBundleExecutable": "BiliMusicPlayer",
        "CFBundleIconFile": "icon.icns",
        "CFBundleIdentifier": "com.bilimusicplayer.desktop",
        "CFBundleInfoDictionaryVersion": "6.0",
        "CFBundleName": "BiliMusicPlayer",
        "CFBundlePackageType": "APPL",
        "CFBundleShortVersionString": version,
        "CFBundleVersion": version,
        "NSHighResolutionCapable": True,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as staging:
        archive = Path(staging) / "player.zip"
        with zipfile.ZipFile(runtime) as libs, zipfile.ZipFile(
            archive, "w", compression=zipfile.ZIP_DEFLATED
        ) as bundle:
            if "libmpv.2.dylib" not in libs.namelist():
                raise ValueError("Missing native macOS libmpv")
            bundle.write(ROOT.parent / "desktopApp/icons/icon.icns", f"{CONTENTS}/Resources/icon.icns")
            bundle.write(exe, f"{CONTENTS}/MacOS/BiliMusicPlayer")
            for name in libs.namelist():
                if "/" in name or not name.endswith(".dylib") or name == "libmediampv.dylib":
                    continue
                with libs.open(name) as source:
                    library_cpu, library_minimum = binary_info(source)
                    minimum = max(minimum, library_minimum)
                    if library_cpu != cpu:
                        raise ValueError(f"Architecture mismatch: {name}")
                    source.seek(0)
                    with bundle.open(f"{CONTENTS}/Frameworks/{name}", "w") as dest:
                        shutil.copyfileobj(source, dest)
            plist["LSMinimumSystemVersion"] = f"{minimum >> 16}.{(minimum >> 8) & 255}.{minimum & 255}"
            bundle.writestr(f"{CONTENTS}/Info.plist", plistlib.dumps(plist))
        archive.replace(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--exe", required=True, type=Path)
    parser.add_argument("--runtime", required=True, type=Path, help="MediaMP macOS runtime JAR")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    build(args.exe, args.runtime, args.output)
