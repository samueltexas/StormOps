# Known Limitations and Roadmap

StormOps intentionally prioritizes an isolated local learning environment and clear engineering boundaries over production deployment.

## Current limitations

| Area | Current boundary | Potential next step |
| --- | --- | --- |
| Deployment | Local kind cluster | Dedicated deployment architecture and threat model |
| Security | Default-on HTTP Basic authentication | HTTPS and managed identities |
| Kubernetes scope | Fixed context, namespace, and demo workload | Singleton allowlists and reviewed runner RBAC |
| Concurrency | Persistent shared-database reservation | Fenced automated crash reconciliation |
| Experiment state | Durable admission, intent and atomic completion | Automated recovery beyond the offline runbook |
| Auditability | Intent persists; crash outcome can remain unknown | Transactional/outbox-style audit strategy |
| Fault catalog | Pod termination and HTTP simulation | Carefully scoped additional experiments |
| Reliability | Local recovery observation | More robust telemetry and SLO evaluation |
| Testing | Unit, integration, frontend, and CI checks | Browser E2E, security and resilience testing |
| Operations | Developer-managed local setup | Reproducible packaging, health checks, runbooks |

## Prioritized roadmap

### P1 — Safety and correctness

- Maintain secure startup defaults and the explicit dev-only opt-out.
- Add stronger HTTP-level tests for unauthorized and forbidden requests, including incorrect credentials.
- Review error handling to prevent leaking infrastructure details.
- Extend offline recovery toward a fenced worker model.

### P2 — Developer experience

- Simplify first-run setup and environment validation.
- Add a recorded demo and real screenshots.
- Add a concise troubleshooting runbook.
- Keep dependency versions and CI configuration current.

### P3 — Advanced engineering

- Evaluate Kubernetes-native client integration and least-privilege RBAC.
- Add richer experiment scheduling and safety policies only after durable coordination exists.
- Extend reliability metrics and SLO-oriented reports.
- Add end-to-end and security regression tests.

## Non-goals of the current release

- Running experiments against production clusters
- Public multi-tenant SaaS hosting
- Guaranteed distributed execution safety
- Formal security certification or independent penetration-test assurance

This document describes current engineering boundaries and possible future work; roadmap entries are not implemented features.

See [v2 safety and recovery](V2_SAFETY.md) for implemented guardrails and their precise limits.
