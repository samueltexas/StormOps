"""Operator regression tests: no Docker, Kubernetes or credentials required."""

import importlib.util
import io
import json
import os
import socket
import subprocess
import stat
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "stormops.py"
SPEC = importlib.util.spec_from_file_location("stormops_operator", SCRIPT)
operator = importlib.util.module_from_spec(SPEC)
import sys
sys.modules[SPEC.name] = operator
SPEC.loader.exec_module(operator)


class CredentialTests(unittest.TestCase):
    def test_private_file_rejects_missing_world_readable_and_symlinks(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            secret = root / "credentials.env"
            self.assertFalse(operator.Inspector.private_file(secret)[0])
            secret.write_text("STORMOPS_ADMIN_PASSWORD=hidden\n")
            secret.chmod(0o644)
            self.assertFalse(operator.Inspector.private_file(secret)[0])
            secret.chmod(0o600)
            self.assertEqual(operator.Inspector.private_file(secret), (True, "Present, mode 0600"))
            alias = root / "alias"
            alias.symlink_to(secret)
            self.assertFalse(operator.Inspector.private_file(alias)[0])

    def test_only_assignment_names_are_returned(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "credentials.env"
            path.write_text("# comment\nexport STORMOPS_ADMIN_PASSWORD='top-secret'\n"
                            "STORMOPS_DB_PASSWORD=another-secret\nOTHER_PASSWORD=hidden\n")
            names = operator.Inspector.assignment_keys(path)
            self.assertEqual(names, {"STORMOPS_ADMIN_PASSWORD", "STORMOPS_DB_PASSWORD"})
            self.assertNotIn("top-secret", str(names))
            self.assertNotIn("another-secret", str(names))


class SafetyTests(unittest.TestCase):
    def test_kubectl_always_uses_explicit_context_and_config(self):
        path = Path("/tmp/stormops-runner.json")
        result = operator.Inspector.kubectl_prefix(path, operator.NAMESPACE)
        self.assertEqual(result[:4], ["kubectl", "--kubeconfig", str(path), "--context"])
        self.assertEqual(result[4], "kind-stormops-local")
        self.assertEqual(result[-2:], ["--namespace", "stormops-lab"])

    def test_command_failure_does_not_echo_output(self):
        with patch.object(operator.subprocess, "run") as mocked:
            mocked.return_value.returncode = 1
            mocked.return_value.stdout = "SECRET_OUTPUT"
            mocked.return_value.stderr = "TOKEN_OUTPUT"
            ok, output = operator.Inspector.command(["kubectl", "get", "secrets"])
            self.assertFalse(ok)
            self.assertEqual(output, "Command returned an error")

    def test_cluster_target_rejects_non_loopback_and_mismatched_servers(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".kube").mkdir()
            inspector = operator.Inspector(root)
            for path in (inspector.admin_config, inspector.runner_config):
                path.write_text("config\n")
                path.chmod(0o600)
            external = json.dumps({"clusters": [{"cluster": {"server": "https://10.0.0.5:6443"}}]})
            with patch.object(operator.Inspector, "command", return_value=(True, external)):
                self.assertFalse(inspector.kube_target())
            first = json.dumps({"clusters": [{"cluster": {"server": "https://127.0.0.1:31234"}}]})
            second = json.dumps({"clusters": [{"cluster": {"server": "https://127.0.0.1:31235"}}]})
            with patch.object(operator.Inspector, "command", side_effect=[(True, first), (True, second)]):
                self.assertFalse(inspector.kube_target())
            with patch.object(operator.Inspector, "command", side_effect=[(True, first), (True, first)]):
                self.assertTrue(inspector.kube_target())

    def test_secure_launcher_blocks_explicit_authentication_opt_out(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".kube").mkdir()
            (root / ".secrets").mkdir()
            (root / "scripts").mkdir()
            inspector = operator.Inspector(root)
            inspector.admin_config.write_text("admin config")
            inspector.runner_config.write_text("runner config")
            inspector.security_file.write_text("STORMOPS_ADMIN_PASSWORD=hidden\n"
                                               "STORMOPS_VIEWER_PASSWORD=hidden\n"
                                               "STORMOPS_SECURITY_ENABLED=false\n")
            inspector.database_file.write_text("STORMOPS_DB_PASSWORD=hidden\n")
            for path in (inspector.admin_config, inspector.runner_config,
                         inspector.security_file, inspector.database_file):
                path.chmod(0o600)
            (root / "scripts/run-api-phase10d.sh").write_text("#!/bin/bash\n")
            identity = json.dumps({"status": {"userInfo": {
                "username": "system:serviceaccount:stormops-lab:stormops-runner"}}})
            with patch.object(operator.Inspector, "command", return_value=(True, identity)):
                with patch.object(operator.Inspector, "kube_target", return_value=True):
                    with patch.object(operator.Inspector, "tcp_open", side_effect=lambda p: p == 15432):
                        reasons = operator.can_start_api(inspector)
            self.assertTrue(any("Authentication is explicitly disabled" in x for x in reasons))

    def test_rbac_reply_distinguishes_denials_from_command_errors(self):
        cases = (
            (0, "yes\n", "", True),
            (1, "no\n", "", False),
            (1, "", "Forbidden", None),
            (1, "no\n", "TLS verification failed", None),
            (0, "no\n", "", None),
        )
        for returncode, stdout, stderr, expected in cases:
            with self.subTest(returncode=returncode, stdout=stdout, stderr=stderr):
                response = subprocess.CompletedProcess(
                    ["kubectl"], returncode, stdout, stderr
                )
                with patch.object(operator.subprocess, "run", return_value=response):
                    self.assertIs(
                        operator.Inspector.access_decision(["kubectl", "auth", "can-i", "get", "secrets"]),
                        expected,
                    )
        with patch.object(operator.subprocess, "run", side_effect=subprocess.TimeoutExpired("kubectl", 8)):
            self.assertIsNone(operator.Inspector.access_decision(["kubectl", "auth", "can-i"]))

    def test_runner_diagnostics_fail_closed_on_unknown_permission(self):
        with tempfile.TemporaryDirectory() as tmp:
            inspector = operator.Inspector(Path(tmp))
            inspector.runner_config.parent.mkdir()
            inspector.runner_config.write_text("synthetic kubeconfig")
            inspector.runner_config.chmod(0o600)
            identity = json.dumps({"status": {"userInfo": {
                "username": "system:serviceaccount:stormops-lab:stormops-runner"
            }}})
            with patch.object(operator.Inspector, "command", return_value=(True, identity)):
                with patch.object(operator.Inspector, "access_decision",
                                  side_effect=[True, None, True]):
                    inspector.runner_identity()
            checks = {item.name: item for item in inspector.checks}
            self.assertEqual(checks["Runner identity"].result, "PASS")
            self.assertEqual(checks["Runner RBAC: list pods"].result, "PASS")
            self.assertEqual(checks["Runner RBAC: get secrets"].result, "FAIL")
            self.assertEqual(checks["Runner RBAC: get secrets"].detail, "Unknown")

    def test_api_preflight_checks_every_runner_permission(self):
        with tempfile.TemporaryDirectory() as tmp:
            inspector = operator.Inspector(Path(tmp))
            inspector.runner_config.parent.mkdir()
            inspector.security_file.parent.mkdir()
            (inspector.root / "scripts").mkdir()
            for file in (inspector.runner_config, inspector.admin_config):
                file.write_text("synthetic kubeconfig")
                file.chmod(0o600)
            inspector.security_file.write_text(
                "STORMOPS_SECURITY_ENABLED=true\n"
                "STORMOPS_ADMIN_PASSWORD=example\n"
                "STORMOPS_VIEWER_PASSWORD=example\n"
            )
            inspector.database_file.write_text("STORMOPS_DB_PASSWORD=example\n")
            for file in (inspector.security_file, inspector.database_file):
                file.chmod(0o600)
            (inspector.root / "scripts/run-api-phase10d.sh").write_text("#!/bin/bash\n")
            identity = json.dumps({"status": {"userInfo": {
                "username": "system:serviceaccount:stormops-lab:stormops-runner"
            }}})
            allowed = [True, True, True, True, False, False, False, False, False]
            scenarios = (
                (allowed, False),
                (allowed[:4] + [True] + allowed[5:], True),
                (allowed[:4] + [None] + allowed[5:], True),
            )
            with patch.dict(operator.os.environ, {"STORMOPS_SECURITY_ENABLED": "true"}):
                with patch.object(operator.Inspector, "kube_target", return_value=True):
                    with patch.object(operator.Inspector, "command", return_value=(True, identity)):
                        with patch.object(operator.Inspector, "tcp_open", side_effect=lambda p: p == 15432):
                            for replies, must_fail in scenarios:
                                with self.subTest(replies=replies):
                                    with patch.object(operator.Inspector, "access_decision",
                                                      side_effect=replies) as decisions:
                                        reasons = operator.can_start_api(inspector)
                                    self.assertEqual(decisions.call_count, 9)
                                    self.assertEqual(bool(reasons), must_fail)
                                    if must_fail:
                                        self.assertTrue(any("Runner RBAC verification failed" in r for r in reasons))

    def test_api_launcher_uses_only_runner_kubeconfig(self):
        launcher = (SCRIPT.parent / "run-api-phase5.sh").read_text(encoding="utf-8")
        self.assertIn('export STORMOPS_KUBECONFIG="$PROJECT/.kube/stormops-runner-config"', launcher)
        self.assertIn('export KUBECONFIG="$STORMOPS_KUBECONFIG"', launcher)
        self.assertNotIn('export KUBECONFIG="$PROJECT/.kube/stormops-config"', launcher)

    def test_http_status_handles_authentication(self):
        from urllib.error import HTTPError
        error = HTTPError("http://127.0.0.1/", 401, "Unauthorized", {}, None)
        with patch.object(operator, "urlopen", side_effect=error):
            self.assertEqual(operator.Inspector.http_status("http://127.0.0.1/"), 401)

    def test_start_api_refuses_admin_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".kube").mkdir()
            (root / ".secrets").mkdir()
            (root / "scripts").mkdir()
            for f in (root / ".kube/stormops-runner-config",
                      root / ".secrets/stormops-security.env",
                      root / ".secrets/stormops-db.env"):
                f.write_text("dummy\n")
                f.chmod(0o600)
            (root / "scripts/run-api-phase10d.sh").write_text("#!/bin/bash\n")
            inspector = operator.Inspector(root)
            answer = json.dumps({"status": {"userInfo": {"username": "admin-user"}}})
            with patch.object(operator.Inspector, "command", return_value=(True, answer)):
                with patch.object(operator.Inspector, "tcp_open", side_effect=lambda p: p == 15432):
                    reasons = operator.can_start_api(inspector)
            self.assertTrue(any("identity" in x for x in reasons))

    def test_start_api_never_executes_with_bad_configuration(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(operator.os, "execve") as mock_exec:
                with patch.object(operator.Inspector, "tcp_open", return_value=False):
                    code = operator.start_api(operator.Inspector(Path(tmp)))
            self.assertEqual(code, 1)
            mock_exec.assert_not_called()

    def test_report_json_is_valid_and_does_not_include_secret(self):
        checks = [operator.Check("Security env", "PASS", "Present, mode 0600")]
        stream = io.StringIO()
        with redirect_stdout(stream):
            operator.print_report(checks, json_mode=True)
        result = json.loads(stream.getvalue())
        self.assertEqual(result["failed"], 0)
        self.assertEqual(result["checks"][0]["name"], "Security env")
        self.assertNotIn("password=", stream.getvalue().lower())

    def test_doctor_fails_if_repository_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            inspector = operator.Inspector(Path(tmp))
            checks = inspector.report()
            self.assertEqual(len(checks), 1)
            self.assertEqual(checks[0].result, "FAIL")


if __name__ == "__main__":
    unittest.main()
