"""Build the Windows x64 player as a self-contained EXE."""
import argparse
import hashlib
import lzma
import os
from pathlib import Path, PureWindowsPath
import shutil
import struct
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
APP = "BiliMusicPlayer.exe"


def entries(app, audio):
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
    if not (audio / "libmpv-2.dll").is_file():
        raise ValueError("Missing libmpv-2.dll in the Windows audio distribution")
    tree(audio, "")
    for name in files:
        if any(parent.as_posix().casefold() in names for parent in PureWindowsPath(name).parents):
            raise ValueError(f"Windows file/directory collision: {name}")
    return dict(sorted(files.items()))


def compress_payload(files, staging):
    # Keep the small file index compiled into the launcher. The stream contains only file bytes.
    members = []
    payload = staging / "payload.xz"
    filters = [{"id": lzma.FILTER_X86}, {"id": lzma.FILTER_LZMA2, "preset": 6}]
    with lzma.open(payload, "wb", filters=filters) as output:
        for name, path in files.items():
            utf16 = name.replace("/", "\\").encode("utf-16-le")
            escaped = "".join(f"\\x{unit:04x}" for (unit,) in struct.iter_unpack("<H", utf16))
            with path.open("rb") as source:
                shutil.copyfileobj(source, output, 1024 * 1024)
                members.append(f'    {{ L"{escaped}", {source.tell()}ULL }},\n')
    with payload.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    index = "".join(members)
    key = hashlib.sha256((digest + index).encode("ascii")).hexdigest()[:24]
    (staging / "bundle.h").write_text(f'#define BUNDLE_KEY L"{key}"\n'
                                      f'#define BUNDLE_DIGEST "{digest}"\n'
                                      'static const struct { const wchar_t *name; ULONGLONG size; } members[] = {\n'
                                      + index + '};\n', encoding="ascii")


def build_launcher(staging, host):
    gcc = os.environ.get("MINGW_CC", "x86_64-w64-mingw32-gcc")
    windres = os.environ.get("MINGW_WINDRES", "x86_64-w64-mingw32-windres")
    subprocess.run([windres, "-J", "coff", "-O", "rc", str(host.resolve()),
                    "-o", "bundle.rc"], cwd=staging, check=True)
    with (staging / "bundle.rc").open("ab") as rc:
        rc.write(b'\n101 RCDATA "payload.xz"\n')
    subprocess.run([windres, "--preprocessor", gcc, "--preprocessor-arg=-E",
                    "--preprocessor-arg=-xc", "--preprocessor-arg=-DRC_INVOKED",
                    "bundle.rc", "-O", "coff", "-o", "bundle.o"], cwd=staging, check=True)
    exe = staging / "player.exe"
    prefix = os.environ.get("MINGW_LZMA_PREFIX")
    lib_flags = ["-I", str(Path(prefix) / "include"), "-L", str(Path(prefix) / "lib")] if prefix else []
    subprocess.run([gcc, "-std=c17", "-Os", "-Wall", "-Wextra", "-Werror",
                    "-municode", "-mwindows", "-static", "-s", "-DLZMA_API_STATIC", *lib_flags, "-I", str(staging),
                    str(HERE / "windows_launcher.c"), "bundle.o", "-o", str(exe),
                    "-Wl,--no-insert-timestamp", "-llzma", "-lbcrypt",
                    "-lshell32", "-lole32", "-luuid"], cwd=staging, check=True)
    return exe


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--asuka-host", type=Path, help="platform/windows from the Asuka 0.2.9 source release")
    source.add_argument("--app", type=Path, help="Package a previously built Windows host")
    parser.add_argument("--audio-runtime", type=Path, required=True, help="Directory containing the Windows x64 audio DLLs and their licenses")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.suffix.lower() != ".exe":
        parser.error("--output must be a standalone .exe")
    output = args.output.resolve()
    if any(output.is_relative_to(path.resolve()) for path in (args.app, args.audio_runtime) if path):
        parser.error("Choose an output outside the input application and audio runtime folders")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=args.output.parent) as tmp:
        stage = Path(tmp).resolve()
        app = args.app
        if app is None:
            project = HERE.parent / "asuka"
            out = stage / "asuka-build"
            subprocess.run([sys.executable, str(project / "build_core.py"), "windows-x64"], check=True)
            # Keep the upstream SDK reusable. Apply the player host fixes in its build copy.
            host = stage / "windows-host"
            shutil.copytree(args.asuka_host / "src", host / "src")
            patches = ["map", "webview", "scroll", "backdrop", "titlebar"]
            subprocess.run(["git", "apply", "--no-index",
                            *(str(HERE / f"asuka-0.2.9-win32-{name}.patch") for name in patches)],
                           cwd=host, check=True)
            subprocess.run(["asuka", "build", "-p", "windows", "-o", str(out), str(project)],
                           env={**os.environ, "ASUKA_WINDOWS_HOST": str(host)}, check=True)
            app = out / "windows-amd64" / "BiliMusicPlayer"
        files = entries(app, args.audio_runtime)
        compress_payload(files, stage)
        result = build_launcher(stage, app / APP)
        result.replace(args.output)
    print(args.output)


if __name__ == "__main__":
    main()
