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

The V3 failure-mode regression suite is **non-destructive**:

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
| PostgreSQL paused after admission | A new admission fails while unavailable; persisted intent and active reservation survive restoration; resume remains blocked | Disposable PostgreSQL/Testcontainers outage integration |
| Backup/restore of audit and experiment history | Restore must preserve completed history, active run ownership, stopped state, audit rows and sequence continuity | Disposable PostgreSQL backup/restore integration test |
| Database network partition during a reserved run | A healthy database remains unreachable through the client path; attempted admission fails, and a recovered connection still refuses reinjection/resume | Verified Toxiproxy + disposable PostgreSQL integration test |

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
- [x] Confirm Java 21/25 CI, PostgreSQL integration, frontend and existing kind E2E
      on the reviewed feature branch.
- [x] Review the test evidence and integrate PR #3.

### Increment 3.2 — Isolated PostgreSQL outage recovery

- [x] Add an exclusive Testcontainers PostgreSQL outage test that pauses and resumes
      only its own database container.
- [x] Verify that no second experiment is admitted during the outage and that the
      active run, synthetic injection intent and safety stop survive restoration.
- [x] Validate on Java 21 and 25 in GitHub Actions and locally.
- [x] Review this increment and integrate PR #4.

Docker pause represents database process unavailability, not a pure TCP network
partition. This test uses no existing StormOps database, container or Kubernetes
resource. The synthetic intent does not indicate an actual injected fault.

### Increment 3.3 — Isolated API process crash and restart

- [x] Add a real Spring Boot JVM crash/restart test using exclusively owned
      Java child processes and a disposable PostgreSQL container.
- [x] Seed synthetic INJECTION_INTENT after the first API starts; do not execute
      any Kubernetes fault or use ambient Kubernetes credentials.
- [x] Assert that the restarted API retains the reservation, rejects another
      injection and a premature resume, and preserves the audit trail.
- [x] Verify Java 21 and Java 25 CI and local execution.
- [x] Review evidence and integrate PR #5.

The test forcibly terminates **only the Java child process it created**, not
the local StormOps API. Its database, credentials and loopback port belong
only to Testcontainers and the test process. A nonexistent kubeconfig is passed
to both child processes. This test demonstrates crash-safe admission blocking,
not successful remediation of an injected fault.

### Increment 3.4 — Backup, Restore & Evidence Verification

- [x] Add a `pg_dump -Fc` / `pg_restore` integration test between two
      independently owned PostgreSQL Testcontainers instances.
- [x] Verify completed history, active reservation, stop state, audit entries,
      Flyway schema history and BIGSERIAL continuity after restoration.
- [x] Assert restored ambiguous intent remains unfinished, and admission/resume
      continue to fail closed.
- [x] Confirm Java 21/25 CI and local integration tests.
- [x] Review results and integrate PR #6.

The archive contains synthetic records only and stays within the test process.
It is not a backup of the established StormOps database and does not restore
any existing Docker volume, API service or Kubernetes object.

### Increment 3.5 — Isolated TCP partition and recovery evidence

- [x] Add a Testcontainers network with an exclusive PostgreSQL target and
      a Toxiproxy client path (no host networking changes).
- [x] Confirm the database process stays healthy while the proxy prevents
      client requests, including any new admission.
- [x] Verify connection restoration does not clear the active run, invent a
      terminal result or enable a new injection/resume.
- [x] Add a non-secret evidence inventory and manual decision runbook.
- [x] Confirm Java 21/25 CI and local integration tests.
- [x] Review the evidence and integrate PR #7.

This test simulates a TCP partition between an application client and an
exclusively test-owned PostgreSQL instance. It does not alter local Docker
networks, firewall settings, kubeconfig, pods or the established database.

See [V3 recovery evidence and limitations](V3_RECOVERY_EVIDENCE.md).

### Milestone 3 acceptance review

- [x] Validate all five incremental pull requests (#3–#7) before integration.
- [x] Confirm the final test increment on the local Mac: 33 Java unit tests,
      15 integration tests, plus 15 Python operator tests after main sync.
- [x] Confirm the final PR CI: 14 successful checks, zero failures or pending
      checks; four tag-only publication checks intentionally skipped.
- [x] Cross-reference the observed coverage and remaining limits in the
      README, testing guide and recovery evidence runbook.
- [x] Review the scope of completed tests against still-unverified recovery
      scenarios. The milestone is complete **for its isolated local-first scope**.

### Residual engineering work beyond Milestone 3

The tests do **not** prove correctness under every network partition or worker
crash. In particular, a real Kubernetes operation interrupted after execution
may still have an unknown outcome and require offline reconciliation. End-to-end
database outage with a live fault worker, concurrent multi-host execution,
recovery-point and recovery-time objectives, and production-grade recovery
are not verified.

These remain possible future engineering work, not Milestone 3 completion
claims. See [recovery evidence and limits](V3_RECOVERY_EVIDENCE.md) and
[known limitations](KNOWN_LIMITATIONS.md).

**Non-goals:** production disaster recovery certification, restoring live
local volumes during development, changing the frontend visual design,
or increasing allowed chaos scope.
