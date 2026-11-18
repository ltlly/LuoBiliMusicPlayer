"""Print the verified x64 WebView2 Fixed Version directory for package_windows.py."""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import urllib.request


VERSION = "157.0.4341.60"
PRODUCT = f"Microsoft.WebView2.FixedVersionRuntime.{VERSION}.x64"
# Microsoft publishes the x64 CAB link at developer.microsoft.com/microsoft-edge/webview2/.
URL = ("https://msedge.sf.dl.delivery.mp.microsoft.com/filestreamingservice/files/"
       f"9d6605ff-c172-41a1-8116-e1bb02b93a01/{PRODUCT}.cab")
CAB_SHA256 = "1bad3df802a4c21f6b271cf3cd640912056291e3915b86fa7577a18899f761b9"
TREE_SHA256 = "3b05cb844c6a8d3ff65e552bd062175efc372444d3ae587b6bb75f1dfafadf43"


def verify_runtime(root):
    """Check every official file including locales, data packs and libraries."""
    digest = hashlib.sha256()
    for path in sorted(root.rglob("*"), key=lambda p: p.relative_to(root).as_posix()):
        if path.is_symlink():
            raise ValueError(f"Unexpected symlink in WebView2: {path}")
        if path.is_file():
            digest.update(path.relative_to(root).as_posix().encode("utf-8") + b"\0")
            with path.open("rb") as source:
                digest.update(hashlib.file_digest(source, "sha256").digest())
    if digest.hexdigest() != TREE_SHA256:
        raise ValueError(f"Incomplete or different WebView2 {VERSION} runtime: {root}")


def verify_cab(path):
    with path.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != CAB_SHA256:
            raise ValueError(f"WebView2 CAB checksum mismatch: {path}")


def prepare(cache):
    directory = cache.resolve() / f"{VERSION}-x64"
    root = directory / "WebView2"
    if root.exists():
        verify_runtime(root)
        return root

    directory.mkdir(parents=True, exist_ok=True)
    cab = directory / f"{PRODUCT}.cab"
    with tempfile.TemporaryDirectory(dir=directory) as tmp:
        stage = Path(tmp)
        if cab.exists():
            verify_cab(cab)
        else:
            download = stage / cab.name
            print(f"Downloading {cab.name} from Microsoft", file=sys.stderr)
            with urllib.request.urlopen(URL, timeout=60) as source, download.open("wb") as dest:
                shutil.copyfileobj(source, dest, 1024 * 1024)
            verify_cab(download)
            download.replace(cab)

        if sys.platform == "win32":
            command = ["expand.exe", str(cab), "-F:*", str(stage)]
        else:
            extractor = "tar" if sys.platform == "darwin" else "bsdtar"
            command = [extractor, "-xf", str(cab), "-C", str(stage)]
        subprocess.run(command, check=True, stdout=sys.stderr)
        extracted = stage / PRODUCT
        verify_runtime(extracted)
        try:
            extracted.rename(root)
        except OSError:
            if not root.is_dir():
                raise
            verify_runtime(root)  # Another build may have populated the shared cache.
    return root


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", type=Path,
                        default=Path.home() / ".local/share/mobile-development/webview2-fixed")
    args = parser.parse_args()
    try:
        print(prepare(args.cache_dir))
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{error}\n")
