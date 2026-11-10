# StormOps — Engineering Portfolio Brief

## One-line summary

**StormOps is a local-first, full-stack Kubernetes chaos engineering laboratory with controlled fault experiments, role-aware access, persistent results, and automated testing.**

## Engineering problem

Distributed systems can fail in ways that are difficult to reproduce. StormOps explores how to run controlled experiments against an isolated Kubernetes demo workload, record the outcome, and inspect reliability signals without depending on a paid cloud environment.

## Architecture at a glance

- **Backend:** Java, Spring Boot, Spring Security, Spring JDBC
- **Frontend:** React, TypeScript, Vite
- **Database:** PostgreSQL, Flyway
- **Infrastructure:** Docker, kind, Kubernetes, `kubectl`
- **Observability:** Prometheus and Grafana
- **Quality:** JUnit, Testcontainers, Vitest, GitHub Actions

See `docs/architecture/ARCHITECTURE.md` for detailed system design.

## Key implemented capabilities

- Controlled pod termination against a fixed local Kubernetes demo workload
- HTTP baseline, latency, and error simulations
- Experiment results and history persisted in PostgreSQL
- Reliability and analytics API endpoints
- ADMIN/VIEWER access control when security is enabled
- Explicit confirmation before fault execution
- Automated unit, integration, frontend, and CI checks

## Engineering decisions worth discussing in an interview

1. **Safety scope:** hard-coded local Kubernetes context, namespace, and workload prevent casual use against arbitrary clusters.
2. **Operational guardrails:** healthy replica checks and bounded recovery observation before/after pod termination.
3. **Persistence:** Flyway migrations and parameterized JDBC queries for experiment history.
4. **Security:** role-based authorization and explicit confirmations, with documented limitations of HTTP Basic and opt-in security.
5. **Test isolation:** Testcontainers-backed PostgreSQL integration tests and non-destructive authorization checks.
6. **Tradeoffs:** in-process locking and completed-result persistence simplify the local prototype but are not sufficient for distributed production control.

## Suggested résumé bullet points

- Built **StormOps**, a full-stack Kubernetes chaos engineering lab using Java/Spring Boot, React/TypeScript, PostgreSQL, Docker, and kind.
- Implemented guarded pod-termination and HTTP fault simulations with experiment history, reliability reporting, and role-based API authorization.
- Added automated unit/integration/frontend testing and GitHub Actions CI, including isolated PostgreSQL integration tests with Testcontainers.

## Demonstration and source

- Repository: https://github.com/samueltexas/StormOps
- Demo guide: `docs/DEMO.md`
- Test evidence: `docs/TESTING.md`
- Security and limitations: `docs/SECURITY.md`, `docs/KNOWN_LIMITATIONS.md`

**Author:** Samuel Texa
