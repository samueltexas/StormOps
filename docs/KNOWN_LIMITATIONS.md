# Known Limitations and Roadmap

StormOps intentionally prioritizes an isolated local learning environment and clear engineering boundaries over production deployment.

## Current limitations

| Area | Current boundary | Potential next step |
| --- | --- | --- |
| Deployment | Local kind cluster | Dedicated deployment architecture and threat model |
| Security | Opt-in HTTP Basic authentication | Secure-by-default configuration, HTTPS, managed identities |
| Kubernetes scope | Fixed context, namespace, and demo workload | Explicit allowlists and least-privilege service accounts |
| Concurrency | Single-process lock | Durable/distributed coordination |
| Experiment state | Completed-result persistence | Durable state machine with crash recovery |
| Auditability | Result persistence may fail after an action | Transactional/outbox-style audit strategy |
| Fault catalog | Pod termination and HTTP simulation | Carefully scoped additional experiments |
| Reliability | Local recovery observation | More robust telemetry and SLO evaluation |
| Testing | Unit, integration, frontend, and CI checks | Browser E2E, security and resilience testing |
| Operations | Developer-managed local setup | Reproducible packaging, health checks, runbooks |

## Prioritized roadmap

### P1 — Safety and correctness

- Make secured startup the clearly documented default for all supported launch paths.
- Add stronger HTTP-level tests for unauthorized and forbidden requests, including incorrect credentials.
- Review error handling to prevent leaking infrastructure details.
- Make experiment state and audit records resilient to process failure.

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
