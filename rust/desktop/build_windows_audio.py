"""Cross-build the player's standalone x64 libmpv with MinGW-w64, Meson and NASM."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request


# Official Debian source packages, fetched from a mainland mirror.
MIRROR = "https://mirrors.huaweicloud.com/debian/pool/main/"
SOURCES = {
    "ffmpeg": ("f/ffmpeg/ffmpeg_8.1.2.orig.tar.xz", "464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c"),
    "ft": ("f/freetype/freetype_2.14.3%2Bdfsg.orig.tar.xz", "dc1bec263a6fffa436029efe6428f601d6b1a0b800837508ee3e49bbab8f1006"),
    "fribidi": ("f/fribidi/fribidi_1.0.16.orig.tar.xz", "1b1cde5b235d40479e91be2f0e88a309e3214c8ab470ec8a2744d82a5a9ea05c"),
    "hb": ("h/harfbuzz/harfbuzz_12.3.2.orig.tar.xz", "6f6db164359a2da5a84ef826615b448b33e6306067ad829d85d5b0bf936f1bb8"),
    "ass": ("liba/libass/libass_0.17.5.orig.tar.gz", "960ce5c828c202b406ed06fcef6130e3a5f4c3a12639cdac5f34507ed526a89a"),
    "placebo": ("libp/libplacebo/libplacebo_7.387.0.orig.tar.gz", "b61470b0909ad36c6d75be8f4cebd6fa1e96d6f302ac8bf7b001791470305f07"),
    "mpv": ("m/mpv/mpv_0.42.0.orig.tar.gz", "8e5f930836acbc702c0057cbe0674d821ad0bc9bedb31b05eb034c4e4af13cdc"),
}
DECODERS = (
    "aac,aac_latm,ac3,eac3,alac,ape,flac,mp1,mp2,mp3,opus,vorbis,wavpack,wmav1,wmav2,"
    "wmapro,wmalossless,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,"
    "pcm_f32le,pcm_f64le,pcm_u8,pcm_alaw,pcm_mulaw"
)


NOTICES = {
    "mpv": ("Copyright", "LICENSE.LGPL"),
    "ffmpeg": ("CREDITS", "LICENSE.md", "COPYING.LGPLv2.1"),
    "ft": ("LICENSE.TXT", "docs/FTL.TXT", "src/bdf/README", "src/pcf/README"),
    "hb": ("COPYING", "AUTHORS", "src/ms-use/COPYING"),
    "fribidi": ("AUTHORS", "COPYING", "gen.tab/unidata/ReadMe.txt"),
    "ass": ("COPYING",),
    "placebo": ("LICENSE",),
}


def audio_notices(src):
    credits = ["BiliMusicPlayer Windows audio runtime",
               "The player dynamically loads libmpv-2.dll. It can be replaced with a compatible build.",
               "Portions of this software are copyright © The FreeType Project (www.freetype.org).",
               "The GCC runtime library exception applies to the statically linked GCC runtime.",
               "Source archives and their SHA-256 checksums are listed below. The build recipe is "
               "rust/desktop/build_windows_audio.py in the player source repository."]
    for project, files in NOTICES.items():
        url, digest = SOURCES[project]
        credits.extend([MIRROR + url, digest])
        for name in files:
            credits.extend([f"{project} — {name}", (src[project] / name).read_text("utf-8", errors="replace")])
    credits.extend(["MinGW-w64 runtime and GCC runtime library exception",
                    Path(__file__).with_name("windows-audio-toolchain-notices.txt").read_text("utf-8"),
                    "GCC runtime — GNU General Public License version 3",
                    (src["ffmpeg"] / "COPYING.GPLv3").read_text("utf-8")])
    return "\n\n".join(credits) + "\n"


def sources(work, cache):
    cache.mkdir(parents=True, exist_ok=True)
    for name, (path, expected) in SOURCES.items():
        archive = cache / Path(path).name
        if not archive.is_file():
            with tempfile.TemporaryDirectory(dir=cache) as temp:
                pending = Path(temp) / archive.name
                with urllib.request.urlopen(MIRROR + path, timeout=90) as response, pending.open("wb") as out:
                    shutil.copyfileobj(response, out)
                with pending.open("rb") as src:
                    if hashlib.file_digest(src, "sha256").hexdigest() != expected:
                        raise ValueError(f"Wrong SHA-256 for {path}")
                pending.replace(archive)
        with archive.open("rb") as src:
            if hashlib.file_digest(src, "sha256").hexdigest() != expected:
                raise ValueError(f"Wrong SHA-256 for {archive}")
        dest = work / name
        dest.mkdir()
        with tarfile.open(archive) as package:
            package.extractall(dest, filter="data")
        (top,) = dest.iterdir()
        yield name, top


def build(work, args):
    src = dict(sources(work, args.cache))
    prefix = work / "install"
    pc = prefix / "lib/pkgconfig"
    env = dict(os.environ, PKG_CONFIG_LIBDIR=str(pc), PKG_CONFIG_PATH="", SOURCE_DATE_EPOCH="0")

    def run(*argv, cwd=work):
        subprocess.run(argv, cwd=cwd, env=env, check=True)

    cross = work / "mingw.ini"
    cross.write_text(f"""[binaries]
c = 'x86_64-w64-mingw32-gcc'
cpp = 'x86_64-w64-mingw32-g++'
ar = 'x86_64-w64-mingw32-gcc-ar'
strip = 'x86_64-w64-mingw32-strip'
windres = 'x86_64-w64-mingw32-windres'
pkg-config = 'pkg-config'
[host_machine]
system = 'windows'
cpu_family = 'x86_64'
cpu = 'x86_64'
endian = 'little'
[properties]
needs_exe_wrapper = true
pkg_config_libdir = '{pc}'
[built-in options]
c_args = ['-ffunction-sections', '-fdata-sections', '-fno-ident']
cpp_args = ['-ffunction-sections', '-fdata-sections', '-fno-ident']
c_link_args = ['-Wl,--gc-sections', '-Wl,--no-insert-timestamp', '-static-libgcc', '-static-libstdc++', '-static']
cpp_link_args = ['-Wl,--gc-sections', '-Wl,--no-insert-timestamp', '-static-libgcc', '-static-libstdc++', '-static']
""")

    def meson(name, *options, library="static"):
        dest = work / ("build-" + name)
        run("meson", "setup", "--cross-file", cross, "--prefix", prefix, "--libdir", "lib",
            "--default-library", library, "--buildtype=minsize", "--strip", "--wrap-mode=nodownload",
            "-Dauto_features=disabled", "-Dprefer_static=true", *options, dest, src[name])
        run("meson", "compile", "-C", dest, "-j", str(args.jobs))
        run("meson", "install", "-C", dest, "--no-rebuild")

    ff = work / "build-ffmpeg"
    ff.mkdir()
    run(src["ffmpeg"] / "configure", f"--prefix={prefix}", "--enable-cross-compile",
        "--target-os=mingw32", "--arch=x86_64", "--cross-prefix=x86_64-w64-mingw32-",
        "--disable-debug", "--disable-doc", "--disable-programs", "--disable-avdevice",
        "--disable-autodetect", "--disable-everything", "--enable-small", "--enable-static",
        "--disable-shared", "--enable-network", "--enable-schannel", "--enable-w32threads",
        "--extra-cflags=-Os -ffunction-sections -fdata-sections",
        "--enable-protocol=file,http,https,tcp,tls,httpproxy,crypto,data",
        "--enable-demuxer=aac,ac3,eac3,aiff,ape,asf,flac,matroska,mov,mp3,ogg,wav,wv,hls,mpegts",
        f"--enable-decoder={DECODERS}", "--enable-parser=aac,aac_latm,ac3,flac,mpegaudio,opus,vorbis",
        "--enable-filter=abuffer,abuffersink,aformat,anull,aresample,atempo,volume", cwd=ff)
    run("make", "-j", str(args.jobs), cwd=ff)
    run("make", "install", cwd=ff)
    # mpv needs its subtitle and rendering dependencies at link time even with video disabled.
    meson("ft", "-Dharfbuzz=disabled", "-Dzlib=disabled")
    meson("fribidi", "-Ddocs=false", "-Dbin=false", "-Dtests=false")
    meson("hb", "-Dtests=disabled", "-Dutilities=disabled",
          "-Dcpp_args=['-DHB_OPTIMIZE_SIZE','-ffunction-sections','-fdata-sections','-fno-ident']")
    meson("ass", "-Drequire-system-font-provider=false")
    # libplacebo's no-Vulkan stubs still include Khronos' platform-neutral headers.
    for subdir in ("vulkan", "vk_video"):
        shutil.copytree(args.vulkan_headers / subdir, src["placebo"] / "src/include" / subdir)
    meson("placebo", "-Ddemos=false", "-Dtests=false")
    # libplacebo's private float formatter uses std::to_chars; its .pc omits the C++ runtime.
    with (pc / "libplacebo.pc").open("a") as f:
        f.write("\nLibs.private: -lstdc++\n")
    meson("mpv", "-Dgpl=false", "-Dcplayer=false", "-Dlibmpv=true", "-Dbuild-date=false",
          "-Dlua=disabled", "-Dgl=disabled", "-Dwasapi=enabled", "-Dwin32-threads=enabled",
          library="shared")
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=args.output) as temp:
        dll = Path(temp) / "libmpv-2.dll"
        notice = Path(temp) / "AUDIO-LICENSES.txt"
        shutil.copyfile(prefix / "bin" / dll.name, dll)
        notice.write_text(audio_notices(src), encoding="utf-8")
        notice.replace(args.output / notice.name)
        dll.replace(args.output / dll.name)
    print(args.output / "libmpv-2.dll")


if __name__ == "__main__":
    rust = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=Path.home() / ".cache/bili-windows-audio/sources")
    parser.add_argument("--vulkan-headers", type=Path, default=Path("/usr/include"))
    parser.add_argument("--output", type=Path, default=rust / "target/windows-audio")
    parser.add_argument("--jobs", type=int, default=min(8, os.cpu_count() or 1))
    options = parser.parse_args()
    if options.jobs < 1:
        parser.error("--jobs must be positive")
    with tempfile.TemporaryDirectory(prefix="bili-windows-audio-") as folder:
        build(Path(folder), options)
