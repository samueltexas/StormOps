#!/usr/bin/env python3
from pathlib import Path
import shutil
import sys

root = Path.home() / "Projects/StormOps"
readme = root / "README.md"
source = root / "docs/assets/screenshots/README-GALLERY.md"
start = "<!-- STORMOPS_SCREENSHOTS_START -->"
end = "<!-- STORMOPS_SCREENSHOTS_END -->"

if not readme.is_file() or not source.is_file():
    sys.exit("ERROR: README or gallery snippet missing")
content = readme.read_text(encoding="utf-8")
gallery = source.read_text(encoding="utf-8")
if start in content or end in content:
    sys.exit("ERROR: Gallery markers already present. No changes made.")
if not content.startswith("# "):
    sys.exit("ERROR: Unexpected README structure. No changes made.")
backup = root / "README.before-gallery.md"
if backup.exists():
    sys.exit("ERROR: Backup filename already exists. No changes made.")
shutil.copy2(readme, backup)
# Add gallery after existing README content, preserving every existing section.
readme.write_text(content.rstrip() + "\n\n" + gallery.rstrip() + "\n", encoding="utf-8")
print("UPDATED: README.md (gallery appended; existing content preserved)")
print("BACKUP: README.before-gallery.md (keep local; do not commit)")
