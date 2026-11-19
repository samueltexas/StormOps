#!/usr/bin/env python3
"""Read-only local diagnostics and explicit service launchers for StormOps."""

import argparse
import json
import os
import re
import shutil
import socket
import stat
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import List, Optional, Sequence, Tuple
from urllib.parse import urlparse
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
CONTEXT = "kind-stormops-local"
NAMESPACE = "stormops-lab"
MONITORING_NAMESPACE = "stormops-monitoring"
ASSIGNMENT = re.compile(r"^\s*(?:export\s+)?(STORMOPS_[A-Z0-9_]+)\s*=")


@dataclass
class Check:
    name: str
    result: str
    detail: str
    recommendation: str = ""


class Inspector:
    """Never uses ambient Kubernetes credentials or modifies local resources."""

    def __init__(self, root: Path = ROOT):
        self.root = root
        self.admin_config = root / ".kube" / "stormops-config"
        self.runner_config = root / ".kube" / "stormops-runner-config"
        self.security_file = root / ".secrets" / "stormops-security.env"
        self.database_file = root / ".secrets" / "stormops-db.env"
        self.checks: List[Check] = []

    def add(self, name: str, result: str, detail: str, recommendation: str = "") -> None:
        self.checks.append(Check(name, result, detail, recommendation))

    @staticmethod
    def command(argv: Sequence[str], timeout: int = 8) -> Tuple[bool, str]:
        try:
            result = subprocess.run(
                list(argv), capture_output=True, text=True, check=False, timeout=timeout
            )
        except (OSError, subprocess.TimeoutExpired):
            return False, "Command unavailable or timed out"
        # Avoid including command output in error messages: it can contain credentials.
        if result.returncode != 0:
            return False, "Command returned an error"
        return True, result.stdout.strip()

    @staticmethod
    def tcp_open(port: int) -> bool:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                return True
        except OSError:
            return False

    @staticmethod
    def http_status(url: str) -> Optional[int]:
        try:
            with urlopen(Request(url, headers={"User-Agent": "StormOps-Operator/1"}), timeout=2) as response:
                return response.status
        except HTTPError as error:
            return error.code
        except (URLError, OSError, ValueError):
            return None

    @staticmethod
    def private_file(path: Path) -> Tuple[bool, str]:
        # Mode checks only; never parse, print or source credential values.
        if path.is_symlink() or not path.is_file():
            return False, "Missing or symbolic-link file"
        try:
            mode = stat.S_IMODE(path.stat().st_mode)
        except OSError:
            return False, "File metadata unavailable"
        if mode != 0o600:
            return False, "Permissions must be 0600 (currently {:04o})".format(mode)
        return True, "Present, mode 0600"

    @staticmethod
    def assignment_keys(path: Path) -> set:
        """Inspect variable *names* only; never return or log secret values."""
        result = set()
        try:
            with path.open(encoding="utf-8") as stream:
                for line in stream:
                    match = ASSIGNMENT.match(line)
                    if match:
                        result.add(match.group(1))
        except (OSError, UnicodeError):
            pass
        return result

    def tools(self) -> None:
        for name in ("python3", "git", "java", "docker", "kubectl", "npm", "node", "kind"):
            executable = self.find(name)
            if executable:
                self.add("Tool: " + name, "PASS", "Available")
            else:
                self.add("Tool: " + name, "FAIL", "Not found", "Install or configure this prerequisite")
        ok, _ = self.command(["docker", "info", "--format", "{{.ServerVersion}}"])
        self.add("Docker engine", "PASS" if ok else "FAIL", "Accessible" if ok else "Unavailable", "Start Docker Desktop" if not ok else "")

    def find(self, name: str) -> Optional[str]:
        if name in ("kind", "helm"):
            project_tool = self.root / ".tools" / "bin" / name
            if project_tool.is_file() and os.access(project_tool, os.X_OK):
                return str(project_tool)
        return shutil.which(name)

    def credentials(self) -> None:
        config_files = (("Security env", self.security_file), ("Database env", self.database_file),
                        ("Admin kubeconfig", self.admin_config), ("Runner kubeconfig", self.runner_config))
        for label, path in config_files:
            good, detail = self.private_file(path)
            self.add(label, "PASS" if good else "FAIL", detail,
                     "Review local credential provisioning; do not paste secrets" if not good else "")

        # Checking names catches incomplete configuration without opening secret values.
        if all(self.private_file(path)[0] for path in (self.security_file, self.database_file)):
            keys = self.assignment_keys(self.security_file) | self.assignment_keys(self.database_file)
            for key in ("STORMOPS_DB_PASSWORD", "STORMOPS_ADMIN_PASSWORD", "STORMOPS_VIEWER_PASSWORD"):
                present = key in keys or bool(os.environ.get(key))
                self.add("Configured: " + key, "PASS" if present else "FAIL",
                         "Variable declared" if present else "Missing variable declaration")

    def kube(self) -> None:
        kind = self.find("kind")
        if kind:
            ok, output = self.command([kind, "get", "clusters"])
            exists = ok and "stormops-local" in output.splitlines()
            self.add("Dedicated kind cluster", "PASS" if exists else "FAIL",
                     "stormops-local found" if exists else "stormops-local not found or Docker unavailable",
                     "Do not recreate an existing cluster without reviewing its state" if not exists else "")
        else:
            self.add("Dedicated kind cluster", "FAIL", "kind is unavailable")

        if not self.private_file(self.admin_config)[0] or not self.find("kubectl"):
            self.add("Kubernetes API", "FAIL", "Explicit StormOps admin kubeconfig unavailable")
            return

        prefix = self.kubectl_prefix(self.admin_config)
        ok, output = self.command(prefix + ["get", "--raw=/readyz"])
        healthy = ok and output.strip() == "ok"
        self.add("Kubernetes API", "PASS" if healthy else "FAIL",
                 "Ready" if healthy else "Not ready or wrong context")
        if not healthy:
            return

        self.deployment("Demo deployment", NAMESPACE, "stormops-demo", 2)
        self.deployment("Prometheus deployment", MONITORING_NAMESPACE, "stormops-prometheus", 1)
        if self.kube_target():
            self.runner_identity()

    @staticmethod
    def kubectl_prefix(config: Path, namespace: Optional[str] = None) -> List[str]:
        result = ["kubectl", "--kubeconfig", str(config), "--context", CONTEXT,
                  "--request-timeout=6s"]
        if namespace:
            result += ["--namespace", namespace]
        return result

    def deployment(self, label: str, namespace: str, name: str, expected: int) -> None:
        cmd = self.kubectl_prefix(self.admin_config, namespace)
        ok, output = self.command(cmd + ["get", "deployment", name, "-o", "json"])
        if not ok:
            self.add(label, "FAIL", "Unavailable")
            return
        try:
            body = json.loads(output)
            desired = int(body.get("spec", {}).get("replicas", 1))
            ready = int(body.get("status", {}).get("readyReplicas", 0))
            valid = desired == expected and ready == expected
        except (ValueError, TypeError, KeyError, json.JSONDecodeError):
            self.add(label, "FAIL", "Invalid Kubernetes response")
            return
        self.add(label, "PASS" if valid else "FAIL",
                 "Ready {}/{} (expected {})".format(ready, desired, expected))

    def kube_target(self) -> bool:
        """Require both explicit kubeconfigs to target the same loopback kind API."""
        servers = []
        for path in (self.admin_config, self.runner_config):
            if not self.private_file(path)[0]:
                self.add("Cluster target", "FAIL", "Dedicated kubeconfig missing or insecure")
                return False
            prefix = self.kubectl_prefix(path)
            ok, output = self.command(prefix + ["config", "view", "--minify", "-o", "json"])
            if not ok:
                self.add("Cluster target", "FAIL", "Could not inspect explicitly selected context")
                return False
            try:
                body = json.loads(output)
                server = body["clusters"][0]["cluster"]["server"]
                host = urlparse(server)
            except (ValueError, TypeError, KeyError, IndexError):
                self.add("Cluster target", "FAIL", "Invalid Kubernetes target configuration")
                return False
            if host.scheme != "https" or host.hostname not in ("127.0.0.1", "localhost", "::1"):
                self.add("Cluster target", "FAIL", "Kubernetes API is not loopback-only")
                return False
            servers.append(server)
        matching = servers[0] == servers[1]
        self.add("Cluster target", "PASS" if matching else "FAIL",
                 "Admin and runner target the same local API" if matching else "Admin and runner target different API endpoints")
        return matching

    def runner_identity(self) -> None:
        if not self.private_file(self.runner_config)[0]:
            self.add("Runner identity", "FAIL", "Dedicated runner kubeconfig missing or insecure")
            return
        prefix = self.kubectl_prefix(self.runner_config, NAMESPACE)
        ok, output = self.command(prefix + ["auth", "whoami", "-o", "json"])
        if not ok:
            self.add("Runner identity", "FAIL", "Token or context unavailable")
            return
        try:
            name = json.loads(output)["status"]["userInfo"]["username"]
        except (ValueError, KeyError, TypeError):
            self.add("Runner identity", "FAIL", "Invalid identity response")
            return
        expected = "system:serviceaccount:stormops-lab:stormops-runner"
        self.add("Runner identity", "PASS" if name == expected else "FAIL",
                 "Dedicated ServiceAccount verified" if name == expected else "Unexpected identity; refusing administrator credentials")
        if name != expected:
            return
        for verb, resource, allowed in (("list", "pods", True), ("get", "secrets", False),
                                        ("delete", "pods", True)):
            ok, output = self.command(prefix + ["auth", "can-i", verb, resource])
            actual = output.strip().lower() == "yes" if ok else False
            passed = actual == allowed
            self.add("Runner RBAC: {} {}".format(verb, resource), "PASS" if passed else "FAIL",
                     "Allowed" if actual else "Denied")

    def ports(self) -> None:
        for label, port in (("PostgreSQL TCP", 15432), ("Demo tunnel TCP", 18081),
                            ("Prometheus tunnel TCP", 19090)):
            open_ = self.tcp_open(port)
            self.add(label, "PASS" if open_ else "WARN",
                     "127.0.0.1:{} is listening".format(port) if open_ else "127.0.0.1:{} is not listening".format(port),
                     "Verify the existing service or port-forward; do not replace unrelated listeners" if not open_ else "")

        for label, url in (("API health", "http://127.0.0.1:18080/actuator/health"),
                           ("UI HTTP", "http://127.0.0.1:15173/"),
                           ("Demo HTTP", "http://127.0.0.1:18081/api/ping"),
                           ("Prometheus HTTP", "http://127.0.0.1:19090/-/ready")):
            code = self.http_status(url)
            result = "PASS" if code == 200 else "WARN"
            self.add(label, result, "HTTP {}".format(code) if code is not None else "Not responding")

    def report(self, include_runtime: bool = True) -> List[Check]:
        self.checks = []
        if not (self.root / "backend" / "pom.xml").is_file():
            self.add("Repository", "FAIL", "Expected StormOps backend/pom.xml not found")
            return self.checks
        self.add("Repository", "PASS", "StormOps project found")
        self.tools()
        self.credentials()
        self.kube()
        if include_runtime:
            self.ports()
        return self.checks


def print_report(checks: Sequence[Check], json_mode: bool = False) -> None:
    if json_mode:
        print(json.dumps({"project": "StormOps", "checks": [asdict(item) for item in checks],
                          "failed": sum(x.result == "FAIL" for x in checks),
                          "warnings": sum(x.result == "WARN" for x in checks)}, indent=2))
        return
    print("StormOps Local Operations — diagnostics (read-only)")
    print("=" * 69)
    for check in checks:
        print("{:<6} {:<33} {}".format(check.result, check.name, check.detail))
        if check.recommendation and check.result != "PASS":
            print("       Next: " + check.recommendation)
    print("-" * 69)
    print("Failures: {} | Warnings: {}".format(
        sum(x.result == "FAIL" for x in checks),
        sum(x.result == "WARN" for x in checks)))


def can_start_api(inspector: Inspector) -> List[str]:
    reasons = []
    for name, path in (("security env", inspector.security_file),
                       ("database env", inspector.database_file),
                       ("runner kubeconfig", inspector.runner_config)):
        good, detail = inspector.private_file(path)
        if not good:
            reasons.append("{}: {}".format(name, detail))
    if not (inspector.root / "scripts" / "run-api-phase10d.sh").is_file():
        reasons.append("Existing secured API launcher is missing")
    if inspector.private_file(inspector.security_file)[0] and inspector.private_file(inspector.database_file)[0]:
        keys = inspector.assignment_keys(inspector.security_file) | inspector.assignment_keys(inspector.database_file)
        for variable in ("STORMOPS_DB_PASSWORD", "STORMOPS_ADMIN_PASSWORD", "STORMOPS_VIEWER_PASSWORD"):
            if variable not in keys and not os.environ.get(variable):
                reasons.append("Missing variable declaration: " + variable)
    # This launcher is intentionally secure; explicit dev-mode opt-out is rejected.
    if os.environ.get("STORMOPS_SECURITY_ENABLED", "").strip().lower() == "false":
        reasons.append("Authentication is explicitly disabled in the environment")
    if inspector.private_file(inspector.security_file)[0]:
        try:
            with inspector.security_file.open(encoding="utf-8") as stream:
                for line in stream:
                    match = re.match(r"^\s*(?:export\s+)?STORMOPS_SECURITY_ENABLED\s*=\s*(.*)$", line)
                    if match:
                        value = match.group(1).split("#", 1)[0].strip().strip("\"'")
                        if value.lower() == "false":
                            reasons.append("Authentication is explicitly disabled in security env")
        except (OSError, UnicodeError):
            reasons.append("Security env cannot be inspected")
    if not inspector.tcp_open(15432):
        reasons.append("PostgreSQL is not listening on 127.0.0.1:15432")
    if inspector.tcp_open(18080):
        reasons.append("API port 18080 is already occupied")
    # Verify that the two explicit credential files point to the same loopback kind API.
    if inspector.private_file(inspector.runner_config)[0] and inspector.private_file(inspector.admin_config)[0]:
        if not inspector.kube_target():
            reasons.append("Runner and admin kubeconfigs do not target the same local API")
    else:
        reasons.append("Admin kubeconfig missing or insecure")
    # The backend refuses to fall back to ambient kubeconfig; assert runner identity.
    if inspector.private_file(inspector.runner_config)[0]:
        prefix = inspector.kubectl_prefix(inspector.runner_config, NAMESPACE)
        ok, output = inspector.command(prefix + ["auth", "whoami", "-o", "json"])
        try:
            valid = ok and json.loads(output)["status"]["userInfo"]["username"] == \
                "system:serviceaccount:stormops-lab:stormops-runner"
        except (ValueError, KeyError, TypeError):
            valid = False
        if not valid:
            reasons.append("Dedicated runner identity could not be verified")
    return reasons


def start_api(inspector: Inspector) -> int:
    reasons = can_start_api(inspector)
    if reasons:
        print("API startup blocked; no services changed:", file=sys.stderr)
        for reason in reasons:
            print("- " + reason, file=sys.stderr)
        return 1
    env = os.environ.copy()
    # Use the reviewed non-admin identity, regardless of any ambient kubeconfig.
    env["STORMOPS_KUBECONFIG"] = str(inspector.runner_config)
    launcher = inspector.root / "scripts" / "run-api-phase10d.sh"
    print("Starting the existing secured API launcher. Flyway may apply pending migrations.", flush=True)
    os.execve("/bin/bash", ["/bin/bash", str(launcher)], env)
    return 0


def start_ui(inspector: Inspector) -> int:
    front = inspector.root / "frontend"
    reasons = []
    if not (front / "package.json").is_file():
        reasons.append("Frontend package.json missing")
    if not (front / "node_modules").is_dir():
        reasons.append("Frontend dependencies missing; run npm ci in frontend first")
    if not shutil.which("npm"):
        reasons.append("npm unavailable")
    if inspector.tcp_open(15173):
        reasons.append("UI port 15173 is already occupied")
    if reasons:
        print("UI startup blocked; no services changed:", file=sys.stderr)
        for reason in reasons:
            print("- " + reason, file=sys.stderr)
        return 1
    os.chdir(front)
    print("Starting the existing Vite frontend on loopback.", flush=True)
    os.execvpe("npm", ["npm", "run", "dev"], os.environ.copy())
    return 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="StormOps local laboratory operations")
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("doctor", "status"):
        sub = commands.add_parser(name, help="Inspect the existing local lab without modifying it")
        sub.add_argument("--json", action="store_true", dest="json_output", help="Emit machine-readable results")
    commands.add_parser("start-api", help="Run the existing secured backend in the foreground")
    commands.add_parser("start-ui", help="Run the existing React frontend in the foreground")
    args = parser.parse_args(argv)
    inspector = Inspector()
    if args.command in ("doctor", "status"):
        checks = inspector.report(include_runtime=True)
        print_report(checks, args.json_output)
        # status is exploratory; doctor exits nonzero if mandatory requirements fail.
        return 1 if args.command == "doctor" and any(x.result == "FAIL" for x in checks) else 0
    if args.command == "start-api":
        return start_api(inspector)
    if args.command == "start-ui":
        return start_ui(inspector)
    parser.error("Unknown command")
    return 2


if __name__ == "__main__":
    sys.exit(main())
