#!/usr/bin/env python3
"""Validate embedded Kubernetes Prometheus configuration with the pinned promtool image."""

from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "infrastructure/kubernetes/monitoring/prometheus.yaml"
IMAGE = "prom/prometheus:v3.5.0"


def embedded_yaml(source: str, key: str) -> str:
    lines = source.splitlines()
    marker = f"  {key}: |"
    if lines.count(marker) != 1:
        raise ValueError(f"Expected exactly one ConfigMap data block: {key}")
    start = lines.index(marker) + 1
    content = []
    for line in lines[start:]:
        if line.startswith("    "):
            content.append(line[4:])
        elif not line.strip():
            content.append("")
        else:
            break
    if not content or not any(part.strip() for part in content):
        raise ValueError(f"Empty ConfigMap data block: {key}")
    return "\n".join(content).rstrip() + "\n"


def validate() -> None:
    manifest = MANIFEST.read_text(encoding="utf-8")
    config = embedded_yaml(manifest, "prometheus.yml")
    rules = embedded_yaml(manifest, "rules.yml")
    if "/etc/prometheus/rules.yml" not in config:
        raise ValueError("Prometheus does not reference the embedded alert rules")
    if "--storage.tsdb.retention.time=24h" not in manifest:
        raise ValueError("Unexpected retention change in the local lab")

    with tempfile.TemporaryDirectory(prefix="stormops-promtool-") as directory:
        # The unprivileged promtool container must read only these synthetic files.
        os.chmod(directory, 0o755)
        for name, content in (("prometheus.yml", config), ("rules.yml", rules)):
            path = Path(directory) / name
            path.write_text(content, encoding="utf-8")
            path.chmod(0o644)
        for args in (
            ("check", "rules", "/etc/prometheus/rules.yml"),
            ("check", "config", "/etc/prometheus/prometheus.yml"),
        ):
            command = [
                "docker", "run", "--rm", "--network", "none",
                "--mount", f"type=bind,src={directory},dst=/etc/prometheus,readonly",
                "--entrypoint", "promtool", IMAGE, *args
            ]
            subprocess.run(command, check=True, timeout=90)
    print("Pinned Prometheus config and rules: verified")


if __name__ == "__main__":
    validate()
