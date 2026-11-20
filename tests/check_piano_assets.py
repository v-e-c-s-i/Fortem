#!/usr/bin/env python3
"""Check piano file names and sizes without reading audio contents."""
import json
from pathlib import Path
import sys
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = json.loads((ROOT / "tests/piano-assets.json").read_text())


def check(target: Path) -> None:
    if target.is_dir():
        directory = target / "app/src/main/assets/piano"
        actual = {p.name: p.stat().st_size for p in directory.glob("*.wav")}
    else:
        with ZipFile(target) as archive:
            actual = {}
            for entry in archive.infolist():
                if "/assets/piano/" in "/" + entry.filename and entry.filename.endswith(".wav"):
                    actual[entry.filename.rsplit("/", 1)[-1]] = entry.file_size
    errors = [f"{name}: expected {size} bytes, found {actual.get(name, 'missing')}"
              for name, size in EXPECTED.items() if actual.get(name) != size]
    errors += [f"Unexpected piano asset: {name}" for name in actual.keys() - EXPECTED.keys()]
    if errors:
        raise SystemExit("Piano bank check failed:\n" + "\n".join(errors))
    print(f"Piano bank OK: {len(EXPECTED)} files with complete expected sizes.")


if __name__ == "__main__":
    check(Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT)
