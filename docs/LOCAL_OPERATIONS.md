# StormOps local operations (V3)

## Purpose and safety boundary

`scripts/stormops.py` is a dependency-free Python 3.9+ command-line interface for inspecting the established StormOps local laboratory and starting its **existing** application launchers. It does not install tools, create Kubernetes clusters, change deployments, provision credentials, stop services or delete Docker volumes.

The CLI always passes the **explicit** project-local Kubernetes kubeconfig and fixed `kind-stormops-local` context to read-only `kubectl` commands. It never uses the ambient/default Kubernetes context. It checks the runner's actual ServiceAccount identity and refuses to launch the API if the runner identity is absent or unexpected. This is a local portfolio lab tool, not a production orchestrator.

## Commands

Run these from the repository root, on the Mac Terminal or the VS Code integrated terminal:

```bash
cd /Users/leandrotexas/Projects/StormOps
python3 scripts/stormops.py doctor
python3 scripts/stormops.py status
python3 scripts/stormops.py status --json
```

| Command | Effect | Kubernetes writes |
| --- | --- | --- |
| `doctor` | Checks local toolchain, Docker, mode-600 files, pinned cluster/context, deployment readiness, runner identity/RBAC, and loopback endpoints. Exits nonzero if any required check fails. | None |
| `status` | Same read-only checks, but exits successfully even if services are down, so the report can assist diagnosis. | None |
| `status --json` | Structured version of the report for automation or bug reports. Review output before sharing; it includes local environment state but not credentials. | None |
| `start-api` | Starts the existing secured `scripts/run-api-phase10d.sh` in the foreground after checking secret files, database port, runner identity and API port. | Backend may perform Flyway database migrations; no direct Kubernetes writes by CLI |
| `start-ui` | Starts the existing Vite frontend with `npm run dev` in the foreground. Requires `npm ci` to have been run already. | None |

To launch the API and UI separately, use two terminal windows. `Ctrl+C` stops the foreground process. The API remains bound to `127.0.0.1:18080` and Vite to `127.0.0.1:15173` through the existing configuration. No public networking is enabled.

## Credential handling

The established launchers expect:

- `.secrets/stormops-security.env`
- `.secrets/stormops-db.env`
- `.kube/stormops-config` (laboratory administrator access for inspection only)
- `.kube/stormops-runner-config` (short-lived `stormops-runner` ServiceAccount for application execution)

All four files must exist locally with owner-only mode `0600`. The CLI reads only environment variable **names** and file permissions, never secret values. It does not generate passwords, print tokens, or source shell code while diagnosing. The existing secured launcher deliberately sources its own local env files when `start-api` is invoked; use only trusted, self-managed credentials.

A runner kubeconfig uses the fixed `kind-stormops-local` context even in disposable E2E tests, and its user must identify as `system:serviceaccount:stormops-lab:stormops-runner`. Never substitute the administrator kubeconfig for this file. Credentials may expire and require operator-controlled renewal according to [CI delivery](CI_DELIVERY.md).

## Existing resources are preserved

The `setup-demo-service.sh` and `setup-monitoring.sh` historical installers are **not** invoked, because they write manifests and may replace committed files. Similarly, the CLI does not call `create-cluster.sh` or `recover-cluster.sh`, and never resets an existing database. If the lab needs recovery, inspect [V2 safety](V2_SAFETY.md) and [local development](LOCAL_DEVELOPMENT.md), review the exact recovery procedure and preserve existing history.

The CLI only checks whether `127.0.0.1:15432`, `18081`, `19090`, and HTTP services are reachable. A listening port does not prove the peer's identity. Health checks may report warnings when the frontend/API/port-forwards are intentionally stopped. For any real chaos operation, use the v2 guardrails: dry-run first, durable kill switch, fixed blast radius, authentication and audit.

## Tests

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover -s scripts/tests -p 'test_stormops_operator.py' -v
```

The tests mock network/process operations, run without Docker or real credentials, and verify that the CLI refuses unexpected identities and insecure credentials. The isolated GitHub Actions workflow `.github/workflows/operator-cli.yml` runs these tests with Python 3.9. No paid infrastructure is provisioned.

## Limitations

This iteration does **not** automatically install or repair a cluster, rotate ServiceAccount tokens, own background processes, configure port-forwards, apply manifests, migrate a database on its own, or provide a production deployment path. These require explicit operational design and safety review. It also does not change the v2.0.0 release or existing E2E suites.
