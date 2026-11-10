# StormOps Demo Walkthrough

## Audience

This walkthrough is intended for technical recruiters, backend engineers, DevOps engineers, and platform engineers evaluating a local-first Kubernetes chaos engineering project.

## Demo goals

Demonstrate role-aware access, controlled fault injection, experiment persistence, reliability reporting, and observability. The demo must run only against the isolated local kind cluster.

## Before recording

- Verify Docker, PostgreSQL, kind, the demo workload, API, frontend, and monitoring services are healthy.
- Confirm the Kubernetes context is `kind-stormops-local` and namespace is `stormops-lab`.
- Start the backend with security enabled.
- Use demonstration credentials; do not show passwords, `.env` files, kubeconfig contents, tokens, or personal data.
- Confirm that the demo deployment has the expected healthy replica state.
- Use a clean browser window and avoid displaying unrelated tabs or notifications.

## Suggested 3–5 minute narrative

**00:00–00:30 — Problem and architecture**
Explain why controlled failure experiments are useful. Show the README architecture diagram and identify Spring Boot, React, PostgreSQL, kind, and Prometheus/Grafana.

**00:30–01:15 — Access control**
Sign in as VIEWER, navigate the read-only views, and explain that VIEWER cannot trigger faults. Avoid exposing credentials.

**01:15–02:00 — Experiment catalog and history**
Show the experiment list, pagination/history, and available HTTP simulation modes. Explain that results are persisted in PostgreSQL.

**02:00–03:15 — Controlled experiment**
Use an ADMIN account to run a non-destructive HTTP simulation first. If the lab is healthy and the recording environment is safe, optionally demonstrate pod termination with explicit confirmation. Explain the guardrails and recovery observation.

**03:15–04:00 — Reliability and observability**
Show reliability reports and, if available, Prometheus/Grafana views. Do not claim metrics or dashboards not visible in the running instance.

**04:00–05:00 — Engineering quality**
Show the GitHub Actions checks, automated tests, architecture documentation, and known limitations. Emphasize the difference between a local lab and a production-grade control plane.

## Screenshot checklist

Capture screenshots from the actual running app, not fabricated UI:

1. Main dashboard / experiment overview
2. Login or role-aware UI (without credentials)
3. Experiment history
4. Reliability/analytics report
5. Grafana or monitoring dashboard, if operational
6. Green GitHub Actions run

Suggested folder: `docs/assets/screenshots/`. Prefer PNG/WebP with sensible dimensions and descriptive filenames. Do not commit screenshots containing credentials, private network details, or identifying personal information.

## Portfolio description

**StormOps — Local-First Kubernetes Chaos Engineering Platform**

A full-stack engineering project that provides controlled Kubernetes pod termination and HTTP fault simulations through a Spring Boot API and React interface. StormOps records experiments in PostgreSQL, exposes reliability and analytics endpoints, integrates local observability, and enforces role-based access when security is enabled. Automated tests and GitHub Actions validate key behavior. Built as an isolated learning and portfolio laboratory, not as a production-ready chaos control plane.

## Evidence over claims

Only demonstrate features that work in the checked-out version. Avoid describing the platform as production-ready, multi-cluster, highly available, or externally audited.
