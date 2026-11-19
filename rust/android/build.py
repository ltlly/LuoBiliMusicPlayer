"""Package the Asuka Android host with the legacy app identity and release signer."""
import argparse
import getpass
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--keystore", type=Path, default=ROOT.parent / "keystore/bilimusic-release.keystore")
parser.add_argument("--gradle", default="gradle", help="Gradle 8.14.3 executable")
parser.add_argument("--abi", choices=["arm64-v8a", "armeabi-v7a", "x86_64", "universal"],
                    default="arm64-v8a", help="Architecture of the signed APK")
args = parser.parse_args()
env = os.environ.copy()
output = ROOT / "target/asuka"
sdk = Path(env["ANDROID_HOME"])
tools = max((sdk / "build-tools").iterdir(), key=lambda p: tuple(int(n) for n in p.name.split(".") if n.isdigit()))
asuka = shutil.which("asuka") or str(Path.home() / ".asuka/bin/asuka")
subprocess.run([sys.executable, "asuka/build_core.py", "android-arm64", "android-arm", "android-x64"], cwd=ROOT, check=True)
subprocess.run([asuka, "build", "-p", "android", "--aar", "-o", output / "android-lib", ROOT / "asuka"], check=True)
subprocess.run([args.gradle, "-p", ROOT / "android", f"-PplayerAbi={args.abi}", "assembleRelease"], check=True)
unsigned = output / "android-gradle/outputs/apk/release/BiliMusicPlayer-release-unsigned.apk"

if not env.get("BILI_STORE_PASSWORD"):
    env["BILI_STORE_PASSWORD"] = getpass.getpass("Release keystore password: ")
env.setdefault("BILI_KEY_PASSWORD", env["BILI_STORE_PASSWORD"])
signed = output / f"BiliMusicPlayer-1.7.4-{args.abi}.apk"
signer = tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
subprocess.run([signer, "sign", "--ks", args.keystore,
                "--ks-pass", "env:BILI_STORE_PASSWORD", "--key-pass", "env:BILI_KEY_PASSWORD",
                "--out", signed, unsigned], env=env, check=True)
subprocess.run([signer, "verify", signed], check=True)
print(signed)
