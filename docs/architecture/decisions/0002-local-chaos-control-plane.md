# ADR 0002: Local Chaos Control Plane

**Status:** Accepted for the local prototype

## Context

The first StormOps experiment needs to manipulate a Kubernetes workload while protecting unrelated Docker containers and Kubernetes resources.

## Decision

- Run the Spring Boot API on `127.0.0.1:18080` on the developer workstation.
- Use the dedicated StormOps kubeconfig and an explicit `kind-stormops-local` context.
- Hard-code the namespace, deployment and pod label selector in the Kubernetes adapter.
- Permit only one experiment at a time.
- Require two healthy demo replicas before pod termination.
- Require a specific confirmation header before destructive operations.
- Use bounded subprocess execution and a 90-second recovery observation window.

## Consequences

- This prototype cannot target arbitrary workloads or external clusters.
- Experiment history is in memory and is lost when the API restarts.
- The confirmation header is **not authentication**; the API must remain bound to loopback.
- The initial recovery metric measures Kubernetes replica readiness, not end-user HTTP availability or an SLO.
- Before multi-user or remote deployment, replace local `kubectl` execution with a scoped Kubernetes client identity, persistent audit storage and real authentication/authorization.
