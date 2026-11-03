"""Package only explicitly allowlisted extension sources, never workspace files."""
from pathlib import Path
import hashlib
import json
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EXT = ROOT / "extension"
version = json.loads((EXT / "manifest.json").read_text(encoding="utf-8"))["version"]
out = ROOT / "dist"
out.mkdir(exist_ok=True)
archive = out / f"gemini-anchor-chrome-edge-{version}.zip"
files = [EXT / "manifest.json", *sorted((EXT / "src").glob("*.js")), *sorted((EXT / "icons").glob("*.png"))]
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
    for file in files:
        bundle.write(file, file.relative_to(EXT))
print(f"{archive.name}  SHA256 {hashlib.sha256(archive.read_bytes()).hexdigest()}")
