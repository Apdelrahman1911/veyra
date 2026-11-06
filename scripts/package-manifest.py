#!/usr/bin/env python3
"""Inventory generated packages without uploading or claiming installability."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import platform

ROOT = Path(__file__).resolve().parents[1]
base = ROOT / "composeApp" / "build" / "compose" / "binaries" / "main"
extension = {"Linux": ".deb", "Darwin": ".dmg", "Windows": ".msi"}.get(platform.system())
if extension is None:
    raise SystemExit("Unsupported package host")
packages = list(base.glob(f"**/*{extension}"))
if not packages:
    raise SystemExit(f"Expected {extension} package missing; skipped packaging tasks are not success")
records = []
for path in sorted(packages):
    if not path.resolve().is_relative_to(base.resolve()):
        raise SystemExit("Escaping package path rejected")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    records.append({"path": str(path.relative_to(ROOT)), "bytes": path.stat().st_size,
                    "sha256": digest.hexdigest(), "architecture": platform.machine(),
                    "signing": "no production signing/notarization configured",
                    "installationTested": False})
result = {"recordedAtUtc": datetime.now(timezone.utc).isoformat(), "packages": records}
output = ROOT / ".work" / "verification"
output.mkdir(parents=True, exist_ok=True)
(output / "packages.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result, indent=2))
