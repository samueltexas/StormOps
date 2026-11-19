# StormOps V3 — Operational Failure Testing

**Maintainer:** Samuel Texa
**Milestone:** V3 Milestone 3 (operational failure testing)
**Scope:** Local-first laboratory and disposable CI resources only. No production fault injection.

## Purpose

V3 Milestone 3 tests whether StormOps stays safe when the API, database, telemetry,
or Kubernetes credentials become unavailable. This work must not weaken the
V2 durable reservation, audit, kill switch, fixed blast radius, or manual recovery policy.

Failure handling favors a blocked experiment over an automatically retried fault.
An injection intent is evidence of a potentially attempted operation, not proof that
the fault occurred or that the workload recovered.

## Test boundary

The initial regression increment is **non-destructive**:

- OperationalFailureTest uses Mockito; it never connects to Kubernetes or a real Prometheus server.
- StormOpsIntegrationIT uses disposable PostgreSQL via Testcontainers. Its synthetic
  INJECTION_INTENT represents an ambiguous in-flight operation; it does not delete a pod.
- Existing GitHub Actions Java 21/25 unit and integration jobs exercise these tests.
- The existing kind E2E still tests a separately authorized pod termination only in
  a uniquely created disposable cluster. This increment does not expand that blast radius.

Never run these failure tests against the established stormops-local cluster or its
PostgreSQL volume. Do not run an API-kill test against a live experiment.

## Failure matrix

| Failure | Verified/expected safe behavior | Evidence |
| --- | --- | --- |
| PostgreSQL unavailable before admission | Admission rejects the run; no external calls, no injection intent | Mockito regression |
| Prometheus unavailable before injection | Experiment fails closed and no pod deletion occurs | Mockito regression |
| Runner credentials invalid during precheck | Experiment fails before an injection intent or deletion | Mockito regression |
| Runner credentials revoked during deletion | Outcome is ABORTED, stop is latched at completion, deletion is not retried | Mockito regression |
| Database completion unavailable after external action | Failure is observable; the application never retries the pod deletion | Mockito regression; durability depends on transactional DB behavior |
| API process lost with outstanding INJECTION_INTENT | Active UUID survives repository reinitialization; new admission and resume are blocked; intent audit persists | Testcontainers integration |
| PostgreSQL outage after admission | Outcome may be unknown until DB is available and records are reviewed | Planned isolated outage E2E |
| Backup/restore of audit and experiment history | Restore must preserve run ownership, stopped state and event sequence | Planned disposable restore test |

## Recovery decision

1. Engage the stop where possible. Stop all API workers and ensure no request or
   Kubernetes subprocess from the failed run is still active.
2. Inspect the persistent active_run UUID, append-only audit and workload state.
   Treat INJECTION_INTENT without a terminal outcome as ambiguous.
3. Restore network/DB/telemetry/credentials without retrying the original fault.
4. Inspect Kubernetes pod identities, ready replicas, baseline HTTP and fresh
   Prometheus evidence. These are separate observations, not proof of fault outcome.
5. Make a PostgreSQL backup before any **manual, reviewed** reconciliation.
   Preserve the original run identity, STARTED time and actor; never fabricate RECOVERED.
6. Record an INTERRUPTED result and RECOVERY_REVIEWED audit for that exact UUID in
   an approved transaction, then clear only that reservation and keep stopped=true.
7. Restart the API, inspect evidence, run a dry-run, and resume explicitly only
   after a separate operational review.

The detailed manual procedure and current limitations remain in
[docs/V2_SAFETY.md](V2_SAFETY.md). This milestone introduces **no** online
force-unlock endpoint, automatic lease expiry or automatic incident repair.

## Milestone acceptance

### Increment 3.1 — Failure-mode regression

- [x] Design a failure matrix that distinguishes tested behavior from planned E2E.
- [x] Add mock-based regressions for admission, telemetry, runner credentials,
      ambiguous deletion and completion failures.
- [x] Add a PostgreSQL integration regression for persisted injection intent and
      restart-safe admission exclusion.
- [ ] Confirm Java 21/25 CI, PostgreSQL integration, frontend and existing kind E2E
      on the new feature branch.
- [ ] Review the test evidence and only then integrate the pull request.

### Remaining Milestone 3 work

- [ ] Add disposable PostgreSQL network-outage experiments with safe cleanup.
- [ ] Add crash tests using independently owned test API processes.
- [ ] Verify backup and restore of experiment history, safety state and audit sequence
      in isolated containers.
- [ ] Provide non-secret, reproducible evidence and restoration runbooks.
- [ ] Prove no automatic unlocking or reinjection in every ambiguous case.

**Non-goals:** production disaster recovery certification, restoring live
local volumes during development, changing the frontend visual design,
or increasing allowed chaos scope.
