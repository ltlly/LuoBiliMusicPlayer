"""Package the native Windows player with the original MediaMP libmpv runtime."""
import argparse
from pathlib import Path
import shutil
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--app", type=Path, required=True, help="Asuka Windows application folder")
parser.add_argument("--runtime", type=Path, required=True, help="mediamp-mpv-runtime-windows-x64 JAR")
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
args.output.parent.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(dir=args.output.parent) as staging:
    dest = Path(staging) / "player.zip"
    with zipfile.ZipFile(args.runtime) as runtime, zipfile.ZipFile(dest, "w", compression=zipfile.ZIP_DEFLATED) as package:
        if "libmpv-2.dll" not in runtime.namelist():
            raise ValueError("Missing native Windows libmpv")
        for required in ["BiliMusicPlayer.exe", "asuka-app.json"]:
            if not (args.app / required).is_file():
                raise ValueError(f"Missing Asuka application file: {required}")
        for file in sorted(args.app.rglob("*")):
            if file.is_file():
                package.write(file, file.relative_to(args.app))
        for info in runtime.infolist():
            name = info.filename
            if name.endswith(".dll") and "/" not in name and name != "mediampv.dll":
                with runtime.open(info) as source, package.open(name, "w") as target:
                    shutil.copyfileobj(source, target)
        package.writestr("启动.bat", '@echo off\r\nstart "" "%~dp0BiliMusicPlayer.exe"\r\n')
    dest.replace(args.output)
print(args.output)
