# StormOps

**Cloud-native chaos engineering and resilience testing platform**

[![StormOps CI](https://github.com/samueltexas/StormOps/actions/workflows/ci.yml/badge.svg)](https://github.com/samueltexas/StormOps/actions/workflows/ci.yml)

StormOps is a local-first engineering project for conducting controlled resilience experiments against a Kubernetes-based demonstration environment, inspecting experiment results, and exploring service reliability. It combines a Java/Spring Boot API, a React/TypeScript interface, PostgreSQL persistence, and infrastructure for monitoring with Prometheus and Grafana.

> **Project scope:** An engineering portfolio and local laboratory, not a production-ready fault-injection service. Use only against workloads you own or are explicitly authorized to test.

## Features

- Backend APIs for experiment execution and result retrieval.
- A React dashboard for interacting with the platform.
- PostgreSQL-backed experiment history with Flyway migrations.
- Authentication and role-aware UI behavior for administrator and viewer accounts in the local environment.
- A sample Java service and Kubernetes manifests for controlled experiments.
- Monitoring configurations for Prometheus and Grafana.
- Automated backend unit tests, PostgreSQL integration tests, frontend tests, and production frontend builds in GitHub Actions.

## Technology stack

| Area | Technologies |
| --- | --- |
| Backend | Java (target 21), Spring Boot 4.1.2, Maven Wrapper |
| Frontend | React 19, TypeScript, Vite 8, Lucide React |
| Data | PostgreSQL, Spring JDBC, Flyway |
| Testing | Spring Boot Test, Testcontainers, Vitest, React Testing Library |
| Infrastructure | Docker, Kubernetes, kind |
| Observability | Prometheus, Grafana |
| CI | GitHub Actions, Temurin JDK 25, Node.js 24 |

## Architecture

```mermaid
flowchart LR
    U[User] --> F[React / TypeScript dashboard]
    F --> A[Spring Boot API]
    A --> D[(PostgreSQL)]
    A --> K[Kubernetes laboratory]
    K --> S[Sample Java service]
    K --> P[Prometheus]
    P --> G[Grafana]
```

This is a conceptual overview. Local setup scripts and Kubernetes manifests are located in `scripts/` and `infrastructure/`.

## Repository layout

```text
StormOps/
├── .github/workflows/ci.yml       # Continuous integration
├── backend/                       # Spring Boot API and tests
├── frontend/                      # React UI and tests
├── demo-services/                 # Java sample workload
├── infrastructure/                # Docker and Kubernetes manifests
├── scripts/                       # Local laboratory scripts
└── docs/                          # Architecture decisions and notes
```

## Requirements

For local development, install:

- JDK 25 (the backend is configured with a Java 21 language target)
- Node.js 24 and npm
- Docker Desktop with a working Docker engine
- `kubectl` and `kind` for the Kubernetes laboratory

The repository includes Maven Wrapper scripts; a separate Maven installation is not required for the backend.

## Run tests locally

### Backend unit tests

```bash
cd backend
./mvnw test
```

### Backend integration tests

Start Docker Desktop first. Integration tests use Testcontainers to provision an isolated PostgreSQL container.

```bash
cd backend
./mvnw -Pintegration-tests verify
```

### Frontend tests and build

```bash
cd frontend
npm ci
npm test
npm run build
```

## Local environment

The application is designed for a local Kubernetes laboratory. Review the scripts in `scripts/` and manifests in `infrastructure/` before running them. Database connection settings and authentication credentials are supplied through environment variables; **do not commit local secrets or Kubernetes credentials**.

The backend's default configuration binds to `127.0.0.1:18080`. The local database configuration expects PostgreSQL on `127.0.0.1:15432`. These defaults are intended for local development, not public exposure.

> **Security note:** Authentication is opt-in in the current backend configuration (`STORMOPS_SECURITY_ENABLED`). Enable and configure it before exposing the API beyond a trusted local environment. The current configuration should not be treated as production hardened.

## Continuous integration

The [StormOps CI workflow](https://github.com/samueltexas/StormOps/actions/workflows/ci.yml) runs on pushes and pull requests. It contains three jobs:

1. **Backend unit tests** — Maven test suite.
2. **Backend integration tests** — Maven Failsafe and isolated PostgreSQL through Testcontainers.
3. **Frontend tests and build** — `npm ci`, Vitest, and Vite production build.

The initial public workflow run completed successfully: [run #42836614444](https://github.com/samueltexas/StormOps/actions/runs/42836614444).

## Documentation

- [Local chaos control-plane decision](docs/architecture/decisions/0002-local-chaos-control-plane.md)
- [PostgreSQL experiment-history decision](docs/architecture/decisions/0003-postgresql-experiment-history.md)
- [Phase 4 notes](docs/PHASE4.md)

## Project status

**Active portfolio project.** The initial platform, automated tests, and CI pipeline are implemented. Documentation and developer experience continue to evolve. Production deployment, multi-tenant isolation, and production-grade security are not claimed.

## Author

**Samuel Texa**
[GitHub: @samueltexas](https://github.com/samueltexas)

## License

No open-source license has been selected yet. All rights are reserved unless a license is added to this repository.

<!-- STORMOPS_SCREENSHOTS_START -->
## Product screenshots

Real screenshots from the local StormOps development environment. The application runs against an isolated kind Kubernetes lab; results shown are historical test data, not production service-level guarantees.

### Administrator overview
![StormOps administrator overview with experiment outcomes and Kubernetes lab](docs/assets/screenshots/overview-admin.png)

### Experiment history
![StormOps experiment history with pod termination and HTTP simulation results](docs/assets/screenshots/experiments.png)

### Resilience analytics
![StormOps analytics showing experiment counts and distinct pod and HTTP timing metrics](docs/assets/screenshots/analytics.png)

### Reliability policy evaluation
![StormOps reliability evaluation with configurable thresholds and historical results](docs/assets/screenshots/reliability.png)

### Observability integration
![StormOps observability page with Grafana link and backend health status](docs/assets/screenshots/observability.png)

### Read-only viewer experience
![StormOps viewer role overview without experiment execution action](docs/assets/screenshots/overview-viewer.png)

> **Security and operations:** The viewer screenshot illustrates UI-level restrictions; authorization is enforced separately by the backend. Grafana availability depends on the local monitoring stack. See [Security](docs/SECURITY.md), [Testing](docs/TESTING.md), and [Known limitations](docs/KNOWN_LIMITATIONS.md).
<!-- STORMOPS_SCREENSHOTS_END -->

<!-- STORMOPS_GRAFANA_START -->
### Live observability — Grafana and Prometheus

Real monitoring of the local StormOps Kubernetes demo service, including service availability, HTTP request throughput, JVM heap memory, and HTTP 5xx error rate.

![StormOps Grafana resilience overview](docs/assets/screenshots/grafana-monitoring.png)

> Local lab screenshot; monitoring access requires the local Kubernetes cluster and port-forwarding.
<!-- STORMOPS_GRAFANA_END -->
