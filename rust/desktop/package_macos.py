"""Package a macOS player and its matching MediaMP audio runtime as a Finder app."""
import argparse
import plistlib
import shutil
import struct
import tempfile
import subprocess
import zipfile
from pathlib import Path


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


def build(app, runtime, output, identity):
    """Retain the native host configuration, resources, Info.plist and entitlements."""
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as tmp:
        stage = Path(tmp)
        bundle = stage / app.name
        shutil.copytree(app, bundle, symlinks=True)
        contents = bundle / "Contents"
        metadata = contents / "Info.plist"
        with metadata.open("rb") as source:
            plist = plistlib.load(source)
        with (contents / "MacOS" / plist["CFBundleExecutable"]).open("rb") as source:
            cpu, minimum = binary_info(source)
        frameworks = contents / "Frameworks"
        frameworks.mkdir(exist_ok=True)
        with zipfile.ZipFile(runtime) as libs:
            if "libmpv.2.dylib" not in libs.namelist():
                raise ValueError("Missing native macOS libmpv")
            for name in libs.namelist():
                if "/" in name or not name.endswith(".dylib") or name == "libmediampv.dylib":
                    continue
                with libs.open(name) as source:
                    library_cpu, library_minimum = binary_info(source)
                    if library_cpu != cpu:
                        raise ValueError(f"Architecture mismatch: {name}")
                    minimum = max(minimum, library_minimum)
                    source.seek(0)
                    library = frameworks / name
                    with library.open("wb") as dest:
                        shutil.copyfileobj(source, dest)
                subprocess.run(["codesign", "--force", "--sign", identity, library], check=True)
        plist["LSMinimumSystemVersion"] = f"{minimum >> 16}.{(minimum >> 8) & 255}.{minimum & 255}"
        with metadata.open("wb") as dest:
            plistlib.dump(plist, dest)
        subprocess.run(["codesign", "--force", "--preserve-metadata=entitlements,flags",
                        "--sign", identity, bundle], check=True)
        subprocess.run(["codesign", "--verify", "--deep", "--strict", bundle], check=True)
        archive = stage / "player.zip"
        subprocess.run(["ditto", "-c", "-k", "--keepParent", bundle, archive], check=True)
        archive.replace(output)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", required=True, type=Path, help="Asuka's built .app bundle")
    parser.add_argument("--runtime", required=True, type=Path, help="MediaMP macOS runtime JAR")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--sign", default="-", help="macOS signing identity (default: ad hoc)")
    args = parser.parse_args()
    build(args.app, args.runtime, args.output, args.sign)
