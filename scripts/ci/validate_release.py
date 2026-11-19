#!/usr/bin/env python3
"""Reject release tags that disagree with the packaged versions or lack release notes."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
tag = sys.argv[1] if len(sys.argv) == 2 else ""
if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", tag):
    raise SystemExit("Expected a version tag such as v2.0.0")
for path in ("backend/pom.xml", "demo-services/sample-java-service/pom.xml"):
    version = ET.parse(root / path).getroot().findtext("{http://maven.apache.org/POM/4.0.0}version")
    if version != tag[1:]:
        raise SystemExit(f"{path}: version {version} does not match {tag}")
if not (root / "docs/releases" / f"{tag}.md").is_file():
    raise SystemExit("Version-specific release notes are missing")
if "MIT License" not in (root / "LICENSE").read_text():
    raise SystemExit("MIT license is missing")
print(f"Release metadata validated: {tag}")
