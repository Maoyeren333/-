"""Copy/download the configured checkpoint and verify its complete SHA-256."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, help="Local C2FNet checkpoint to copy")
    parser.add_argument("--check", action="store_true", help="Only verify installed weights")
    args = parser.parse_args()
    item = json.loads((ROOT / "weights/manifest.json").read_text(encoding="utf-8"))["weights"][0]
    directory = Path(os.environ.get("RS_WEIGHTS_DIR") or ROOT / "weights").expanduser().resolve()
    target = directory / item["file"]

    def valid(path):
        if not path.is_file() or path.stat().st_size != item["size_bytes"]:
            return False
        with path.open("rb") as handle:
            return hashlib.file_digest(handle, "sha256").hexdigest() == item["sha256"]

    if valid(target):
        print(f"Verified: {target}")
        return
    if args.check:
        raise SystemExit("Weights missing or checksum mismatch")
    if not args.source and not item["url"]:
        raise SystemExit("No public download URL configured; use --source PATH_TO_C2FNet-39.pth")
    directory.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=directory, suffix=".part", delete=False) as handle:
        temporary = Path(handle.name)
    try:
        if args.source:
            with args.source.open("rb") as source, temporary.open("wb") as out:
                shutil.copyfileobj(source, out)
        else:
            with urllib.request.urlopen(item["url"], timeout=60) as source, temporary.open("wb") as out:
                shutil.copyfileobj(source, out)
        if not valid(temporary):
            raise SystemExit("Downloaded/copied checkpoint failed SHA-256 or size validation")
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"Installed and verified: {target}")


if __name__ == "__main__":
    main()
