"""Build the Windows x64 player as a self-contained EXE."""
import argparse
import hashlib
import os
from pathlib import Path, PureWindowsPath
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib

from get_webview2 import verify_runtime

HERE = Path(__file__).resolve().parent
APP = "BiliMusicPlayer.exe"
ENGINE = "EBWebView/x64/EmbeddedBrowserWebView.dll"
BLOCK = 32768


def entries(app, runtime, webview, staging):
    for rel in [APP, "asuka-app.json"]:
        if not (app / rel).is_file():
            raise ValueError(f"Missing Asuka file: {rel}")
    files = {}
    names = {".bili-bundle"}

    def add(name, path):
        parts = PureWindowsPath(name).parts
        if ("\\" in name or PureWindowsPath(name).anchor or not parts or any(
                part in (".", "..") or part.endswith((".", " ")) or
                any(ord(c) < 32 or c in '<>:"|?*' for c in part) or
                PureWindowsPath(part).is_reserved() for part in parts)):
            raise ValueError(f"Invalid Windows file name: {name}")
        if name.casefold() in names:
            raise ValueError(f"Duplicate Windows file name: {name}")
        names.add(name.casefold())
        files[name] = path

    def tree(root, prefix):
        for source in sorted(root.rglob("*")):
            if source.is_symlink():
                raise ValueError(f"Expected regular files in runtime: {source}")
            if source.is_file():
                add(prefix + source.relative_to(root).as_posix(), source)

    tree(app, "")
    with zipfile.ZipFile(runtime) as jar:
        if "libmpv-2.dll" not in jar.namelist():
            raise ValueError("Missing libmpv-2.dll in the MediaMP runtime")
        for member in jar.infolist():
            name = member.filename
            if name.lower().endswith(".dll") and not any(c in name for c in "/\\") and name != "mediampv.dll":
                path = staging / name
                add(name, path)
                with jar.open(member) as source, path.open("xb") as dest:
                    shutil.copyfileobj(source, dest, 1024 * 1024)
    for rel in ("msedgewebview2.exe", ENGINE):
        if not (webview / rel).is_file():
            raise ValueError(f"Invalid x64 Fixed Version Runtime root: missing {rel}")
    tree(webview, "WebView2/")
    return dict(sorted(files.items()))


def cabinet(files, dest):
    """Write one standard MSZIP cabinet folder. Its records use deterministic DOS dates."""
    table = bytearray()
    size = 0
    for name, path in files.items():
        length = path.stat().st_size
        if length + size > BLOCK * 65535 or len(files) > 65535:
            raise ValueError("Payload exceeds a single CAB folder")
        table.extend(struct.pack("<IIHHHH", length, size, 0, 0x21, 0, 0xA0))
        table.extend(name.replace("/", "\\").encode("utf-8") + b"\0")
        size += length

    with dest.open("wb") as output:
        # CFHEADER (36), CFFOLDER (8), CFFILE[]; CFDATA is compressed below.
        output.write(bytes(44))
        output.write(table)
        blocks = 0
        pending = bytearray()
        history = b""

        def write_block():
            nonlocal blocks, history
            codec = zlib.compressobj(6, zlib.DEFLATED, -15, zdict=history)
            packed = b"CK" + codec.compress(pending) + codec.flush()
            output.write(struct.pack("<IHH", 0, len(packed), len(pending)))
            output.write(packed)
            history = bytes(pending)
            pending.clear()
            blocks += 1

        for path in files.values():
            with path.open("rb") as source:
                while chunk := source.read(BLOCK - len(pending)):
                    pending.extend(chunk)
                    if len(pending) == BLOCK:
                        write_block()
        if pending:
            write_block()
        total = output.tell()
        output.seek(0)
        output.write(struct.pack("<4sIIIII BBHHHHH", b"MSCF", 0, total, 0, 44, 0, 3, 1,
                                 1, len(files), 0, 1, 0))
        output.write(struct.pack("<IHH", 44 + len(table), blocks, 1))
    with dest.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def build_launcher(staging, digest, host):
    gcc = os.environ.get("MINGW_CC", "x86_64-w64-mingw32-gcc")
    windres = os.environ.get("MINGW_WINDRES", "x86_64-w64-mingw32-windres")
    (staging / "bundle.h").write_text(f'#define BUNDLE_KEY L"{digest[:24]}"\n'
                                      f'#define BUNDLE_DIGEST "{digest}"\n', encoding="ascii")
    subprocess.run([windres, "-J", "coff", "-O", "rc", str(host.resolve()),
                    "-o", "bundle.rc"], cwd=staging, check=True)
    with (staging / "bundle.rc").open("ab") as rc:
        rc.write(b'\n101 RCDATA "payload.cab"\n')
    subprocess.run([windres, "--preprocessor", gcc, "--preprocessor-arg=-E",
                    "--preprocessor-arg=-xc", "--preprocessor-arg=-DRC_INVOKED",
                    "bundle.rc", "-O", "coff", "-o", "bundle.o"], cwd=staging, check=True)
    exe = staging / "player.exe"
    subprocess.run([gcc, "-std=c17", "-Os", "-Wall", "-Wextra", "-Werror",
                    "-municode", "-mwindows", "-static", "-s", "-I", str(staging),
                    str(HERE / "windows_launcher.c"), "bundle.o", "-o", str(exe),
                    "-Wl,--no-insert-timestamp", "-lcabinet", "-lbcrypt", "-ladvapi32",
                    "-lshell32", "-lole32", "-luuid"], cwd=staging, check=True)
    return exe


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--asuka-host", type=Path, help="platform/windows from the Asuka 0.2.9 source release")
    source.add_argument("--app", type=Path, help="Package a previously built Windows host")
    parser.add_argument("--runtime", type=Path, required=True, help="MediaMP Windows x64 JAR")
    parser.add_argument("--webview-runtime", type=Path, required=True, help="Extracted Microsoft WebView2 Fixed Version x64 root")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.suffix.lower() != ".exe":
        parser.error("--output must be a standalone .exe")
    output = args.output.resolve()
    if any(output.is_relative_to(path.resolve()) for path in (args.app, args.webview_runtime) if path):
        parser.error("Choose an output outside the input application and WebView2 folders")
    verify_runtime(args.webview_runtime)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=args.output.parent) as tmp:
        stage = Path(tmp).resolve()
        app = args.app
        if app is None:
            project = HERE.parent / "asuka"
            out = stage / "asuka-build"
            subprocess.run([sys.executable, str(project / "build_core.py"), "windows-x64"], check=True)
            # Keep the upstream SDK reusable. Only this player build uses the patched node table.
            host = stage / "windows-host"
            shutil.copytree(args.asuka_host / "src", host / "src")
            subprocess.run(["git", "apply", "--no-index", str(HERE / "asuka-0.2.9-win32-map.patch")],
                           cwd=host, check=True)
            subprocess.run(["asuka", "build", "-p", "windows", "-o", str(out), str(project)],
                           env={**os.environ, "ASUKA_WINDOWS_HOST": str(host)}, check=True)
            app = out / "windows-amd64" / "BiliMusicPlayer"
        files = entries(app, args.runtime, args.webview_runtime, stage)
        digest = cabinet(files, stage / "payload.cab")
        result = build_launcher(stage, digest, app / APP)
        result.replace(args.output)
    print(args.output)


if __name__ == "__main__":
    main()
