# Published image end-to-end verification

## Scope

This CI job verifies the **immutable, published v2.0.0 images**, rather than only rebuilding source JARs. It extends the existing disposable-kind test without changing production infrastructure or broadening the application fault allowlist.

- API image: `ghcr.io/samueltexas/stormops-api@sha256:fb8a11d7d0137540fe7bd8eecc8cf5148f6be32b56bf5db20d1992c86a068221`
- Demo image: `ghcr.io/samueltexas/stormops-demo@sha256:8e89830873b0982a89bf453ff7a895f9a49841d829df24a035103bf49609f1f2`

The refs are taken from the [v2.0.0 release](https://github.com/samueltexas/StormOps/releases/tag/v2.0.0); do not retag them or replace them with `latest`.

## Execution boundary

The checked-in E2E script retains its original JAR-based mode when `STORMOPS_E2E_API_IMAGE` and `STORMOPS_E2E_DEMO_IMAGE` are absent. When both are supplied, it requires digest-pinned GHCR references and Linux amd64. A fresh kind cluster, short-lived least-privilege Kubernetes token, and temporary PostgreSQL container are created for the run.

The published API runs in a container with **Linux host networking**, needed because v2 uses fixed loopback addresses for the demo and Prometheus. Host networking is constrained to a disposable GitHub-hosted Linux runner; it is **not** a production deployment pattern. The kubeconfig is read-only mounted, remains mode 0600, and is read by a matching non-root UID/GID. The demo's published digest is pulled and tagged under a unique temporary local name before loading into kind, preserving the exact image bytes and the existing `imagePullPolicy: Never` behavior.

The test asserts deny-by-default RBAC, initial kill switch, dry-run, exactly one pod termination, recovery, audit events, persisted result after a container restart, and renewed stop. The test deletes only resources bearing its generated unique names. Logs are sanitized before being uploaded as CI artifacts.

## Running

Open a pull request changing the workflow or E2E harness to trigger this separate test. It also supports a manual GitHub Actions `workflow_dispatch` after the workflow is available on the default branch. Keep the existing `kind-e2e` job: it covers JAR build behavior independently from the published images.

Do not run this release-image mode on Docker Desktop for Apple Silicon: the v2.0.0 images target Linux amd64 and this scenario needs Linux host networking. The script will reject unsupported hosts before any resources are created.

## Limitations

This proves the exact published images in one GitHub-hosted Linux amd64 environment. It does not establish native ARM64 compatibility, public hosting readiness, zero-downtime guarantees, or production security. No existing StormOps cluster is selected or modified.
