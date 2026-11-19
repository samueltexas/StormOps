#!/usr/bin/env python3
"""Real fault test in a uniquely named, disposable cluster; never uses ambient credentials."""
import base64
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "artifacts" / "e2e"


def run(*args, input=None, env=None, timeout=180):
    return subprocess.run(args, input=input, text=True, capture_output=True,
                          env=env, timeout=timeout, check=True).stdout


def eventually(check, timeout=120):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except (urllib.error.URLError, OSError, ValueError, subprocess.CalledProcessError) as error:
            last = type(error).__name__
        time.sleep(2)
    raise AssertionError(f"Condition did not converge within {timeout}s ({last})")


def free_port(port=0):
    with socket.socket() as connection:
        connection.bind(("127.0.0.1", port))
        return connection.getsockname()[1]


def main():
    for command in ("kind", "kubectl", "docker", "java"):
        if not shutil.which(command):
            raise RuntimeError(f"Required executable missing: {command}")
    for artifact in (ROOT / "backend/target/stormops-api.jar", ROOT / "demo-services/sample-java-service/target/stormops-demo.jar"):
        if not artifact.is_file():
            raise RuntimeError(f"Build the current Maven package first: {artifact.name}")
    # Refuse to replace an existing local tunnel or service.
    free_port(18081)
    free_port(19090)
    api_port = free_port()
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    suffix = uuid.uuid4().hex[:12]
    cluster = "stormops-e2e-" + suffix
    demo_image = "stormops/sample-java-service:e2e-" + suffix
    db_name = "stormops-e2e-db-" + suffix
    processes = []
    cluster_owned = False
    db_owned = False
    image_owned = False
    stop_traffic = threading.Event()
    password = secrets.token_urlsafe(32)
    viewer_password = secrets.token_urlsafe(32)
    db_password = secrets.token_urlsafe(32)
    token = ""

    with tempfile.TemporaryDirectory(prefix="stormops-e2e-") as directory:
        temp = Path(directory)
        admin = temp / "admin.json"
        runner = temp / "runner.json"
        logs = []

        def kubectl(*args, identity=admin):
            return run("kubectl", "--kubeconfig", str(identity), "--request-timeout=15s", *args)

        def apply(path):
            kubectl("apply", "-f", str(ROOT / path))

        def request(path, method="GET", confirmation=None, user="admin", body=None):
            headers = {}
            if user:
                secret = password if user == "admin" else viewer_password
                headers["Authorization"] = "Basic " + base64.b64encode(f"{user}:{secret}".encode()).decode()
            if confirmation:
                headers["X-StormOps-Confirmation"] = confirmation
            if body is not None:
                headers["Content-Type"] = "application/json"
            req = urllib.request.Request(f"http://127.0.0.1:{api_port}{path}",
                                         method=method, headers=headers,
                                         data=None if body is None else json.dumps(body).encode())
            try:
                with urllib.request.urlopen(req, timeout=130) as response:
                    return response.status, json.load(response)
            except urllib.error.HTTPError as error:
                raw = error.read()
                return error.code, json.loads(raw) if raw else {}

        def pods():
            return json.loads(kubectl("get", "pods", "-n", "stormops-lab", "-l", "app=stormops-demo", "-o", "json"))["items"]

        def uid_set():
            return {pod["metadata"]["uid"] for pod in pods()}

        def save(name, data):
            (EVIDENCE / name).write_text(json.dumps(data, indent=2) + "\n")

        def launch_api():
            jar = ROOT / "backend/target/stormops-api.jar"
            log = temp / f"backend-{len(logs)}.log"
            logs.append(log)
            env = dict(os.environ)
            # Do not inherit a local profile or security overrides.
            for key in list(env):
                if key.startswith(("SPRING_", "STORMOPS_", "SERVER_", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")):
                    env.pop(key)
            env.update(STORMOPS_DB_URL=f"jdbc:postgresql://127.0.0.1:{db_port}/stormops",
                       STORMOPS_DB_USER="stormops", STORMOPS_DB_PASSWORD=db_password,
                       STORMOPS_ADMIN_PASSWORD=password, STORMOPS_VIEWER_PASSWORD=viewer_password,
                       STORMOPS_KUBECONFIG=str(runner), SERVER_PORT=str(api_port))
            with log.open("w") as output:
                process = subprocess.Popen(["java", "-jar", str(jar)], stdout=output,
                                           stderr=subprocess.STDOUT, env=env, start_new_session=True)
            processes.append(process)
            def ready():
                if process.poll() is not None:
                    raise RuntimeError("Backend exited before becoming healthy")
                return request("/actuator/health")[0] == 200
            eventually(ready)
            return process

        try:
            print(f"Creating isolated cluster {cluster}", flush=True)
            assert cluster not in run("kind", "get", "clusters").splitlines()
            config = temp / "kind.yaml"
            config.write_text("""kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    extraPortMappings:
      - containerPort: 30081
        hostPort: 18081
        listenAddress: "127.0.0.1"
      - containerPort: 30090
        hostPort: 19090
        listenAddress: "127.0.0.1"
""")
            cluster_owned = True
            run("kind", "create", "cluster", "--name", cluster, "--kubeconfig", str(admin),
                "--config", str(config), "--image", "kindest/node:v1.36.1", "--wait", "120s", timeout=300)
            os.chmod(admin, 0o600)
            kubectl("create", "namespace", "stormops-lab")
            kubectl("create", "namespace", "stormops-monitoring")
            apply("infrastructure/kubernetes/chaos-rbac.yaml")

            # Generate a short-lived ServiceAccount token; never print or upload it.
            source = json.loads(kubectl("config", "view", "--raw", "--minify", "-o", "json"))
            token = kubectl("create", "token", "stormops-runner", "-n", "stormops-lab", "--duration=1h").strip()
            runner.write_text(json.dumps({"apiVersion": "v1", "kind": "Config",
                "clusters": [{"name": "e2e", "cluster": source["clusters"][0]["cluster"]}],
                "users": [{"name": "runner", "user": {"token": token}}],
                "contexts": [{"name": "kind-stormops-local", "context": {"cluster": "e2e", "user": "runner", "namespace": "stormops-lab"}}],
                "current-context": "kind-stormops-local"}))
            os.chmod(runner, 0o600)
            identity = json.loads(kubectl("auth", "whoami", "-o", "json", identity=runner))["status"]["userInfo"]["username"]
            assert identity == "system:serviceaccount:stormops-lab:stormops-runner"
            checks = []
            for verb, resource, namespace, expected in [
                ("list", "pods", "stormops-lab", True),
                ("get", "pods", "stormops-lab", True),
                ("delete", "pods", "stormops-lab", True),
                ("get", "deployments/stormops-demo", "stormops-lab", True),
                ("get", "secrets", "stormops-lab", False),
                ("create", "pods", "stormops-lab", False),
                ("patch", "deployments/stormops-demo", "stormops-lab", False),
                ("delete", "pods", "default", False),
                ("get", "nodes", "stormops-lab", False),
            ]:
                try:
                    answer = kubectl("auth", "can-i", verb, resource, "-n", namespace, identity=runner).strip() == "yes"
                except subprocess.CalledProcessError as error:
                    assert error.returncode == 1 and error.stdout.strip() == "no"
                    answer = False
                assert answer == expected, (verb, resource, namespace, answer)
                checks.append(dict(verb=verb, resource=resource, namespace=namespace, allowed=answer))
            save("rbac.json", {"identity": identity, "checks": checks})
            print("Dedicated identity and least-privilege assertions passed", flush=True)

            run("docker", "build", "-t", demo_image, str(ROOT / "demo-services/sample-java-service"), timeout=600)
            image_owned = True
            run("kind", "load", "docker-image", demo_image, "--name", cluster, timeout=180)
            manifest = (ROOT / "infrastructure/kubernetes/demo-service.yaml").read_text().replace("stormops/sample-java-service:local", demo_image)
            run("kubectl", "--kubeconfig", str(admin), "apply", "-f", "-", input=manifest)
            kubectl("patch", "service", "stormops-demo", "-n", "stormops-lab", "--type=merge", "-p",
                    '{"spec":{"type":"NodePort","ports":[{"port":8080,"targetPort":8080,"nodePort":30081}]}}')
            apply("infrastructure/kubernetes/monitoring/prometheus.yaml")
            kubectl("patch", "service", "stormops-prometheus", "-n", "stormops-monitoring", "--type=merge", "-p",
                    '{"spec":{"type":"NodePort","ports":[{"port":9090,"targetPort":9090,"nodePort":30090}]}}')
            kubectl("rollout", "status", "deployment/stormops-demo", "-n", "stormops-lab", "--timeout=120s")
            kubectl("rollout", "status", "deployment/stormops-prometheus", "-n", "stormops-monitoring", "--timeout=120s")

            def traffic():
                while not stop_traffic.is_set():
                    try:
                        with urllib.request.urlopen("http://127.0.0.1:18081/api/simulations/baseline", timeout=2) as response:
                            response.read()
                    except (OSError, urllib.error.URLError):
                        pass
                    stop_traffic.wait(0.1)
            threading.Thread(target=traffic, daemon=True).start()
            env = dict(os.environ, POSTGRES_PASSWORD=db_password)
            run("docker", "run", "--detach", "--name", db_name, "-e", "POSTGRES_PASSWORD", "-e", "POSTGRES_USER=stormops",
                "-e", "POSTGRES_DB=stormops", "-p", "127.0.0.1::5432", "postgres:17-alpine", env=env)
            db_owned = True
            db_port = int(run("docker", "port", db_name, "5432/tcp").strip().rsplit(":", 1)[1])
            eventually(lambda: run("docker", "exec", db_name, "pg_isready", "-U", "stormops"))
            api = launch_api()
            assert request("/api/v1/safety", user=None)[0] == 401
            assert request("/api/v1/safety/resume", "POST", "RESUME_STORMOPS", user="viewer")[0] == 403
            assert request("/api/v1/safety")[1]["stopped"] is True
            before = uid_set()
            assert len(before) == 2
            assert request("/api/v1/experiments/pod-termination?dryRun=false", "POST", "TERMINATE_STORMOPS_DEMO_POD")[0] == 409
            assert uid_set() == before

            print("Waiting for healthy baseline and real Prometheus traffic", flush=True)
            def dry_run():
                code, body = request("/api/v1/experiments/pod-termination", "POST", "TERMINATE_STORMOPS_DEMO_POD")
                return code == 200 and body.get("status") == "DRY_RUN"
            eventually(dry_run, timeout=120)
            assert uid_set() == before
            assert request("/api/v1/safety/resume", "POST", "RESUME_STORMOPS")[0] == 200
            save("pods-before.json", {"uids": sorted(before)})
            print("Executing one real pod termination through the authenticated API", flush=True)
            code, result = request("/api/v1/experiments/pod-termination?dryRun=false", "POST", "TERMINATE_STORMOPS_DEMO_POD")
            save("experiment.json", result)
            assert code == 200 and result["status"] == "RECOVERED", result
            after = uid_set()
            assert len(after) == 2 and len(before - after) == 1 and len(after - before) == 1
            save("pods-after.json", {"uids": sorted(after)})
            audit = request("/api/v1/safety/audit")[1]
            events = [event for event in audit if event["run_id"] == result["id"]]
            save("audit.json", events)
            assert {"STARTED", "PRECHECK_PASSED", "INJECTION_INTENT", "POSTCHECK_PASSED", "RECOVERED"} <= {event["event"] for event in events}
            assert all(event["actor"] == "admin" for event in events)
            assert request("/api/v1/safety")[1]["active_run"] is None
            api.terminate()
            api.wait(timeout=20)
            launch_api()
            assert request("/api/v1/experiments/" + result["id"])[1]["status"] == "RECOVERED"
            assert request("/api/v1/safety/stop", "POST", "STOP_STORMOPS")[0] == 200
            assert request("/api/v1/experiments/pod-termination?dryRun=false", "POST", "TERMINATE_STORMOPS_DEMO_POD")[0] == 409
            assert uid_set() == after
            save("summary.json", {"status": "PASSED", "identity": identity, "podsReplaced": 1, "resultPersistedAfterRestart": True})
            print("PASS: one pod replaced, service recovered, audit persisted, stop enforced", flush=True)
        finally:
            stop_traffic.set()
            for process in reversed(processes):
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait()
            for log in logs:
                content = log.read_text()
                for secret in (password, viewer_password, db_password, token):
                    if secret:
                        content = content.replace(secret, "[REDACTED]")
                (EVIDENCE / log.name).write_text(content)
            # Delete only resources created under this run's unique names.
            for command in (["docker", "rm", "-f", db_name] if db_owned else [],
                            ["kind", "delete", "cluster", "--name", cluster] if cluster_owned else [],
                            ["docker", "image", "rm", demo_image] if image_owned else []):
                if command:
                    try:
                        run(*command, timeout=120)
                    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                        print(f"Cleanup failed for {command[-1]}; inspect this test resource manually", flush=True)


if __name__ == "__main__":
    def interrupted(signum, frame):
        raise KeyboardInterrupt("E2E interrupted; cleaning up owned resources")
    signal.signal(signal.SIGTERM, interrupted)
    main()
