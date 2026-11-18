"""Build and sign the native Android application with the local SDK and NDK."""
import argparse
import getpass
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ABIS = {
    "x86_64": "x86_64-linux-android",
    "x86": "i686-linux-android",
    "arm64-v8a": "aarch64-linux-android",
    "armeabi-v7a": "armv7-linux-androideabi",
}


def latest(parent):
    return max((p for p in parent.iterdir() if p.is_dir() and p.name[0].isdigit()),
               key=lambda p: tuple(int(n) for n in p.name.split(".") if n.isdigit()))


def build(args):
    sdk = Path(os.environ["ANDROID_HOME"])
    ndk = Path(os.environ["ANDROID_NDK_HOME"]) if "ANDROID_NDK_HOME" in os.environ else latest(sdk / "ndk")
    host = {"Linux": "linux", "Darwin": "darwin", "Windows": "windows"}[platform.system()]
    clang = ndk / "toolchains/llvm/prebuilt" / (host + "-x86_64") / "bin"
    tools = latest(sdk / "build-tools")
    jar = max((sdk / "platforms").glob("android-[0-9]*/android.jar"),
              key=lambda p: int(p.parent.name.removeprefix("android-")))
    profile = "debug" if args.debug else "release"
    out = ROOT / "target" / "apk" / profile
    out.mkdir(parents=True, exist_ok=True)
    suffix = ".exe" if host == "windows" else ""
    launcher = ".cmd" if host == "windows" else ""
    apksigner = tools / ("apksigner.bat" if host == "windows" else "apksigner")
    signer_env = os.environ.copy()
    if not signer_env.get("BILI_STORE_PASSWORD"):
        signer_env["BILI_STORE_PASSWORD"] = getpass.getpass("Release keystore password: ")
    signer_env.setdefault("BILI_KEY_PASSWORD", signer_env["BILI_STORE_PASSWORD"])
    with tempfile.TemporaryDirectory(dir=out) as tmp:
        stage = Path(tmp)
        apk = stage / "app.apk"
        resources = stage / "res.zip"
        subprocess.run([tools / ("aapt2" + suffix), "compile", "--dir", ROOT.parent / "app/src/main/res",
                        "-o", resources], check=True)
        link = [tools / ("aapt2" + suffix), "link", "-I", jar, "--manifest",
                ROOT / "android/AndroidManifest.xml", "-o", apk, resources]
        if args.debug:
            link.append("--debug-mode")
        subprocess.run(link, check=True)
        classes = stage / "classes"
        classes.mkdir()
        javac = Path(os.environ["JAVA_HOME"]) / "bin" / ("javac" + suffix)
        subprocess.run([javac, "--release", "8", "-classpath", jar, "-d", classes,
                        *(ROOT / "android").glob("*.java")], check=True)
        d8 = tools / ("d8.bat" if host == "windows" else "d8")
        subprocess.run([d8, "--min-api", "26", "--lib", jar, "--output", stage,
                        *classes.rglob("*.class")], check=True)
        with zipfile.ZipFile(apk, "a", compression=zipfile.ZIP_DEFLATED) as bundle:
            bundle.write(stage / "classes.dex", "classes.dex")
        for abi in args.abi or ABIS:
            target = ABIS[abi]
            triple = target.replace("armv7-", "armv7a-")
            cc = str(clang / (triple + "26-clang" + launcher))
            env = os.environ.copy()
            var = target.replace("-", "_")
            env.update({"ANDROID_NDK": str(ndk), "CC_" + var: cc, "CXX_" + var: str(clang / (triple + "26-clang++" + launcher)),
                        "AR_" + var: str(clang / ("llvm-ar" + suffix)), "CARGO_TARGET_" + var.upper() + "_LINKER": cc})
            command = ["cargo", "rustc", "--locked", "--lib", "--target", target, "--target-dir", ROOT / "target"]
            if not args.debug:
                command.append("--release")
            command.extend(["--", "-C", "link-arg=-Wl,-z,defs"])
            subprocess.run(command, check=True, cwd=ROOT, env=env)
            name = "libbili_music_player.so"
            lib = stage / abi / name
            lib.parent.mkdir()
            shutil.copyfile(ROOT / "target" / target / profile / name, lib)
            subprocess.run([clang / ("llvm-strip" + suffix), "--strip-unneeded", lib], check=True)
            with zipfile.ZipFile(apk, "a", compression=zipfile.ZIP_DEFLATED) as bundle:
                bundle.write(lib, f"lib/{abi}/{name}")
        aligned = stage / "aligned.apk"
        subprocess.run([tools / ("zipalign" + suffix), "4", apk, aligned], check=True)
        signed = stage / "signed.apk"
        subprocess.run([apksigner, "sign", "--ks", args.keystore, "--ks-pass", "env:BILI_STORE_PASSWORD",
                        "--key-pass", "env:BILI_KEY_PASSWORD", "--out", signed, aligned], check=True, env=signer_env)
        subprocess.run([apksigner, "verify", signed], check=True)
        dest = out / "BiliMusicPlayer.apk"
        os.replace(signed, dest)
        print(dest)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=ABIS, action="append", help="build only these ABIs (default: all)")
    parser.add_argument("--debug", action="store_true")
    parser.add_argument("--keystore", type=Path, default=ROOT.parent / "keystore/bilimusic-release.keystore")
    build(parser.parse_args())
