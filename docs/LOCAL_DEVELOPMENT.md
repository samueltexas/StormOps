# Local Development Guide

## Purpose

StormOps is designed to run locally with no mandatory recurring cloud-hosting cost. It combines a Spring Boot backend, React/TypeScript frontend, PostgreSQL, a dedicated kind Kubernetes cluster, and optional Prometheus/Grafana monitoring.

## Prerequisites

- macOS or a compatible development environment
- Java toolchain compatible with the repository's Maven configuration
- Node.js and npm compatible with the frontend package configuration
- Docker Desktop, `kubectl`, and `kind`
- Git and a terminal

Use the versions pinned or validated by the repository and CI when possible.

## Repository layout

```text
StormOps/
├── backend/                 # Spring Boot API and tests
├── frontend/                # React + TypeScript UI
├── database/                # Database-related project files
├── docs/                    # Architecture and operational documentation
├── scripts/                 # Local setup and startup helpers
├── .github/workflows/       # Continuous integration
└── README.md
```

## Getting started

1. Clone the repository and inspect `README.md` and the scripts under `scripts/`.
2. Start Docker Desktop.
3. Follow the repository's existing setup scripts to provision the dedicated kind cluster, demo workload, PostgreSQL, and monitoring components. Inspect scripts before execution; they may change local Docker/Kubernetes state.
4. Create the local security environment file **outside Git tracking** at `.secrets/stormops-security.env`, using the variable names documented below. Set file permissions to `600`.
5. Start the secured backend with `scripts/run-api-phase10d.sh` and the frontend using the package scripts in `frontend/package.json`.
6. Verify the local application before running any experiment.

### Security environment variable names

```text
STORMOPS_SECURITY_ENABLED
STORMOPS_ADMIN_USERNAME
STORMOPS_ADMIN_PASSWORD
STORMOPS_VIEWER_USERNAME
STORMOPS_VIEWER_PASSWORD
```

Set `STORMOPS_SECURITY_ENABLED=true` for the secured startup path. Never commit real values.

## Local endpoints

The established development layout uses:

| Service | Local address |
| --- | --- |
| Backend API | `http://127.0.0.1:18080` |
| Frontend | `http://127.0.0.1:15173` |
| PostgreSQL | `127.0.0.1:15432` |
| Grafana | `http://127.0.0.1:13000` |
| Demo service | `http://127.0.0.1:18081` |

These are development conventions, not public deployment URLs. Verify actual bindings in the running configuration.

## Safe operating practices

- Use only the dedicated `kind-stormops-local` context and `stormops-lab` namespace.
- Never point the application at a production cluster.
- Keep `.kube/stormops-config` and `.secrets/` out of Git.
- Check that credentials are loaded and security is enabled before opening the UI.
- Run pod termination only when you intend to disrupt the local demo workload.
- Do not publish localhost services through tunneling or port forwarding to the Internet.

## Troubleshooting

- **Docker unavailable:** start Docker Desktop and retry.
- **Kubernetes context missing:** inspect the local kind cluster and kubeconfig; do not substitute a production context.
- **Login fails:** verify security is enabled and the local environment file is present with `600` permissions; avoid printing passwords.
- **Backend fails to start:** check local PostgreSQL availability, environment variables, and Maven logs.
- **Frontend cannot reach API:** verify the configured loopback port and frontend API base URL.

## Cleanup

Consult the project's setup scripts before deleting clusters, containers, or volumes. PostgreSQL volumes can contain experiment history. Do not run destructive cleanup commands without backing up data you intend to retain.
