# StormOps V3 — Operational Observability

**Maintainer:** Samuel Texa
**Scope:** Read-only authenticated API in a local-first laboratory
**No automatic remediation, no new fault types, no UI design changes**

## Operating principle

The existing Prometheus guard checks scrape freshness, target availability,
non-actuator traffic and the HTTP 5xx ratio before a fault can be injected.
The durable PostgreSQL safety record is authoritative for stop state and any
outstanding experiment reservation. These are deliberately separate signals.

V3 introduces `GET /api/v1/observability/status` for ADMIN and VIEWER users.
It combines those signals **only for inspection**, without making the result
an authorization decision or bypassing prechecks.

| Field | Meaning |
| --- | --- |
| `operationalState` | `UNKNOWN`, `REVIEW_REQUIRED`, `STOPPED`, `TELEMETRY_NOT_READY`, or `PRECHECK_REQUIRED` |
| `safetyState` | `UNAVAILABLE`, `INVALID`, `REVIEW_REQUIRED`, `STOPPED`, or `UNRESERVED` |
| `activeRun` | Reserved experiment UUID (null if no reservation) |
| `stopped` | Persisted operator stop flag, when available |
| `telemetryState` | `NOT_CHECKED`, `HEALTHY`, or `UNAVAILABLE_OR_UNSAFE` |
| `interpretation` | Explicit warning that this is read-only evidence |

**No state is named READY or SAFE_TO_EXECUTE.** `PRECHECK_REQUIRED`
means that additional Kubernetes, HTTP, identity, timing and admission checks
must still pass during any independently authorized request.

If the database is unavailable, return HTTP 503 and `UNKNOWN` without
claiming anything about the kill switch, reservation or Prometheus. If the
database is available but the telemetry guard fails, do not reveal raw
Prometheus replies, internal exception messages, credentials or endpoints.

## Boundaries and regression tests

- The controller does not call any Kubernetes mutation or write to PostgreSQL.
- `ObservabilityControllerTest` checks the safety-state precedence, unknown
  database handling, degraded telemetry, and lack of write operations.
- Existing integration tests verify HTTP authentication and role access.
- The active reservation always takes precedence over a healthy telemetry
  result. Stopping the system remains a separate explicit operator action.
- The API binds to loopback by default and exposes no public Prometheus endpoint.

## Existing monitoring and remaining limitations

Prometheus and Grafana are configured under
`infrastructure/kubernetes/monitoring/`, and the existing
`/api/v1/reliability/report` is historical experiment evaluation, **not**
live uptime, SLO compliance or an error budget.

A true service-level reliability dashboard with historical windowed SLI/SLO
evaluation, Grafana panels and alert rules remains a separate implementation
decision. The view must not be presented as active automation or an
incident-management system. No production uptime guarantee is made.
