# StormOps v2.0 safety and recovery

## Design and implementation sequence

The v1 baseline had opt-in authentication, process-local locking and result-only persistence. A crash after injection could lose the result and permit another experiment. v2 adds secure startup, durable admission and intent auditing, bounded health checks, and explicit operator recovery. The backend and demo service use release version `2.0.0`. See [release notes](releases/v2.0.0.md) for scope and upgrade requirements.

1. Secure defaults and MIT licensing.
2. PostgreSQL reservation, atomic completion and audit trail.
3. Fixed blast radius, dry-run, telemetry checks and persistent kill switch.
4. Isolated regression tests and an offline recovery procedure.

## Startup and compatibility

Set `STORMOPS_DB_PASSWORD`, `STORMOPS_ADMIN_PASSWORD` and `STORMOPS_VIEWER_PASSWORD` through the existing local secret mechanism. Never commit values. Authentication defaults to enabled; false outside the `dev` profile aborts startup. Even `dev` keeps authentication enabled unless `STORMOPS_SECURITY_ENABLED=false` is explicitly set. The server remains on loopback.

Flyway V2 creates `safety_control` and `experiment_audit` without modifying V1 or deleting history. The initial stop is engaged. Existing clients, including the unchanged frontend, now perform **dry-runs** when they omit `dryRun`. Live execution requires an explicit API request with `?dryRun=false`; there are no new visual controls in this increment.

## Fixed safety policy

The allowlists are deliberately singleton and cannot be widened by a request:

- Context: `kind-stormops-local`.
- Namespace: `stormops-lab`.
- Label selector: `app=stormops-demo`; deployment: `stormops-demo`.
- Exactly two ready, running, non-terminating, ReplicaSet-managed matching pods before injection.
- Maximum affected pods: **one**. Maximum concurrent experiments: **one**, across API instances sharing this database, including dry-runs.
- Pod deletion uses an immutable UID precondition. No force deletion, scaling or arbitrary command/URL input.
- Deadline: 90 seconds, checked between bounded calls. A call already admitted may finish after the deadline; Kubernetes subprocesses have a 12-second bound, HTTP simulation an 8-second bound and each telemetry request a 3-second read timeout. This is cooperative cancellation, not a hard real-time guarantee.

The application reads `.kube/stormops-runner-config`, not the old administrator kubeconfig. Provision this separate identity from `infrastructure/kubernetes/chaos-rbac.yaml` after reviewing it. The role permits pod get/list/delete only within the lab and get on the named deployment. Kubernetes RBAC cannot restrict pod deletion by label; label checks are application controls. Keep unrelated workloads out of this namespace. Do not copy administrator credentials into the runner file. No manifests are automatically applied.

## Steady state and abort conditions

Both pod and HTTP experiments require healthy replicas, an HTTP 200 baseline and Prometheus evidence before injection. Postchecks repeat across at least 16 seconds (the checked-in scrape interval is 15 seconds). Pod replacement is observed before postchecks. Expected HTTP 503 alone is not considered recovery.

Prometheus is accessed only at `http://127.0.0.1:19090`. If needed, manually open a loopback port-forward to the lab Prometheus service. Real non-actuator traffic must have a positive one-minute rate; empty, malformed, non-finite, stale or unavailable telemetry fails closed. The `up` sample must be no older than 45 seconds and equal 1. The one-minute 5xx ratio must not exceed 5%. These are fixed initial policy values, not per-request overrides. See the [Prometheus HTTP API](https://prometheus.io/docs/prometheus/latest/querying/api/) and [query semantics](https://prometheus.io/docs/prometheus/latest/querying/basics/).

The monitor checks between bounded operations; it cannot observe every transient or cancel an HTTP request or Kubernetes deletion already sent. A short request-scoped fault can finish before the next scrape. No metric result is a guarantee of service availability.

Dry-run performs read-only Kubernetes checks and baseline HTTP probes and writes audit/history records, but never deletes pods or invokes latency/error injection. It is permitted while the stop is engaged, provided no reservation exists.

## Operator API

All mutations require ADMIN (or explicit insecure dev mode) and their confirmation header. VIEWER can only read.

- `GET /api/v1/safety`: persistent stop and active run ID.
- `GET /api/v1/safety/audit`: latest 100 durable events, including actor, run, time and outcome. Older events remain in PostgreSQL.
- `POST /api/v1/safety/stop`, header `X-StormOps-Confirmation: STOP_STORMOPS`: latch the stop. The next execution checkpoint aborts; already admitted work may finish.
- `POST /api/v1/safety/resume`, header `X-StormOps-Confirmation: RESUME_STORMOPS`: clear the stop only when no run is reserved. Review workload health and audit first.
- Existing experiment POST endpoints retain their confirmation headers. `dryRun=true` is the default; `dryRun=false` explicitly requests injection after all checks pass.

No CORS permission is configured. Mutation confirmation headers are non-simple browser headers. HTTP Basic remains loopback-only without TLS; it is not itself CSRF protection. No cookies or server sessions are used. Reassess the header-based browser boundary if adding origins or new mutation routes.

## Durable lifecycle and recovery

Admission atomically claims the singleton and appends STARTED before external work. PRECHECK_PASSED and INJECTION_INTENT precede injection. Completion commits the experiment result, outcome audit and reservation release in one transaction. A failed write keeps the reservation. Any failure after an injection attempt latches the stop. Audit events are append-only through the application, not tamper-proof against database administrators. Rejected authentication/invalid confirmation requests do not create experiment audit records.

Pod deletion is irreversible. Kubernetes owns replacement through its deployment controller; StormOps never blindly recreates, scales or retries it. HTTP faults are request-scoped and leave no configuration to roll back. Abort stops further injection and latches the stop; it does not claim to undo a completed action.

A restart does not expire or clear an unfinished reservation. This intentionally sacrifices availability to prevent duplicate faults. To reconcile a crash:

1. Engage the stop and shut down **all** API instances/workers. Ensure no old subprocess or HTTP request can still inject a fault.
2. Inspect the active run and its audit events. An INJECTION_INTENT means the outcome may be unknown, even when there is no completion event.
3. Verify deployment readiness, replacement pod identities, baseline requests and telemetry. Resolve infrastructure problems manually; never retry the fault as recovery.
4. Back up the database. In a reviewed database transaction, record an `INTERRUPTED` result and `RECOVERY_REVIEWED` audit event for that exact active UUID, then clear only its reservation while keeping `stopped=true`. Preserve original STARTED time and actor; use the reviewing operator as the recovery actor. Do not fabricate a RECOVERED result.
5. Restart, inspect the audit/history, run a dry-run, and explicitly resume only after review.

There is deliberately no online force-unlock endpoint or automatic lease expiry: either could race a surviving worker. Recovery requires database operator access. A future fenced worker design can automate reconciliation without silently increasing the blast radius.

## Validation and scope

Unit tests cover dry-run, precondition rejection, unavailable audit/telemetry, intent-before-injection, abort latching, successful postchecks and timeouts. PostgreSQL Testcontainers tests exercise migration, persistent exclusion, rollback of failed completion and HTTP authorization with security defaults. They never invoke the real Kubernetes gateway or fault target. No paid services are required.

The unchanged UI and legacy reliability summaries are not a complete safety console. Use the safety API and audit for operational decisions. Production deployment, broader namespaces, managed identity, adaptive policies and automatic infrastructure repair remain outside this increment.

## Kubernetes E2E and delivery

The dedicated runner kubeconfig path can now be explicitly set with `STORMOPS_KUBECONFIG` for isolated execution. The [CI guide](CI_DELIVERY.md) describes the JDK 21/25 matrix, real disposable-kind fault proof, ServiceAccount identity checks and gated image publishing. The established lab is not migrated or exercised automatically.
