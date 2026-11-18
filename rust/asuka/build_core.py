"""Build the Rust archive consumed by Asuka's platform linker."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TARGETS = {
    "linux-x64": "x86_64-unknown-linux-gnu",
    "windows-x64": "x86_64-pc-windows-gnu",
    "macos-x64": "x86_64-apple-darwin",
    "macos-arm64": "aarch64-apple-darwin",
    "android-x64": "x86_64-linux-android",
    "android-arm64": "aarch64-linux-android",
    "android-arm": "armv7-linux-androideabi",
}
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("platform", choices=TARGETS, nargs="+")
parser.add_argument("--debug", action="store_true")
args = parser.parse_args()
for platform in args.platform:
    target = TARGETS[platform]
    env = dict(os.environ)
    if platform.startswith("android"):
        ndk = Path(env["ANDROID_NDK_HOME"])
        compilers = list((ndk / "toolchains/llvm/prebuilt").glob("*/bin"))
        if len(compilers) != 1:
            raise RuntimeError("Cannot locate the NDK host toolchain")
        compiler = target.replace("armv7-", "armv7a-") + "33-clang"
        env["CC_" + target.replace("-", "_")] = str(compilers[0] / compiler)
        env["AR_" + target.replace("-", "_")] = str(compilers[0] / "llvm-ar")
    command = ["cargo", "build", "--locked", "--lib", "--target", target]
    if not args.debug:
        command.append("--release")
    subprocess.run(command, cwd=ROOT, env=env, check=True)
    out = ROOT / "asuka/native" / platform
    out.mkdir(parents=True, exist_ok=True)
    archive = Path(env.get("CARGO_TARGET_DIR", ROOT / "target")) / target / ("debug" if args.debug else "release") / "libbili_music_player.a"
    shutil.copyfile(archive, out / archive.name)
    if platform == "windows-x64":
        compiler = os.environ.get("CC_x86_64_pc_windows_gnu", "x86_64-w64-mingw32-gcc")
        runtime = subprocess.check_output([compiler, "-print-file-name=libgcc_eh.a"], text=True).strip()
        shutil.copyfile(runtime, out / "libgcc_eh.a")
