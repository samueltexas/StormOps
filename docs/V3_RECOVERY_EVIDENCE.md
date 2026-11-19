# StormOps V3 — Recovery Evidence and Operational Boundaries

**Maintainer:** Samuel Texa
**Scope:** V3 operational failure testing in isolated environments
**Guarantee level:** Verified test behavior, not production disaster-recovery certification

## Evidence inventory

| Scenario | Test | Safety assertion |
| --- | --- | --- |
| PostgreSQL admission failure | `OperationalFailureTest` | No external fault is attempted |
| Prometheus precheck failure | `OperationalFailureTest` | A pod is not deleted |
| Kubernetes credential failure | `OperationalFailureTest` | No replay or retry after ambiguous deletion |
| Durable intent across process restart | `ApiCrashRecoveryIT` | Same active run and audit remain; new injection and resume are rejected |
| PostgreSQL process unavailability | `PostgreSqlOutageIT` | A second admission fails; reservation survives restoration |
| PostgreSQL logical backup and restore | `BackupRestoreSafetyIT` | Historical outcomes, audit, Flyway, stop and sequence survive |
| PostgreSQL TCP partition | `DatabaseNetworkPartitionIT` | Client access fails while DB stays healthy; intent remains reserved after healing |
| Real Kubernetes pod replacement | `scripts/ci/kind_e2e.py` | Runs only in a disposable kind cluster during CI |

The tests use synthetic experiment identities for the recovery scenarios.
An `INJECTION_INTENT` event means an operation may have been attempted; it
does not prove a fault happened or that the workload recovered.

## Verified Milestone 3 evidence (November 19, 2026)

The work was integrated as five independently reviewed pull requests:

| Increment | Pull request | Verified scope |
| --- | --- | --- |
| 3.1 | [PR #3](https://github.com/samueltexas/StormOps/pull/3) | Failure-mode mock regressions and persisted intent |
| 3.2 | [PR #4](https://github.com/samueltexas/StormOps/pull/4) | Disposable PostgreSQL unavailability |
| 3.3 | [PR #5](https://github.com/samueltexas/StormOps/pull/5) | Isolated API process crash and restart |
| 3.4 | [PR #6](https://github.com/samueltexas/StormOps/pull/6) | PostgreSQL logical backup and restore |
| 3.5 | [PR #7](https://github.com/samueltexas/StormOps/pull/7) | TCP partition, retained intent and recovery runbook |

Final incremental validation: **33 Java unit tests, 15 Java integration tests**
passed on the developer's Mac; the post-merge operator suite passed
**15 Python tests**. The final feature PR completed **14 successful GitHub
Actions checks**, with **four tag-only release checks skipped**, and no
failed or pending checks. Java 21 and 25 were covered by the CI integration
matrix, along with frontend builds and the disposable-kind E2E.

These counts document one reviewed baseline, not a permanent guarantee for
future commits. The linked pull requests and CI logs are the traceable record.
The existing kind E2E intentionally performs one fault in a separately owned
test cluster; no disaster-recovery test targets a production cluster.

## Reproduce the non-destructive regression suite

Run from the repository root in the **Mac Terminal**, with Docker Desktop active:

```bash
cd "$HOME/Projects/StormOps/backend"
./mvnw --batch-mode --no-transfer-progress test
./mvnw --batch-mode --no-transfer-progress -Pintegration-tests verify
```

The integration profile starts and removes its own PostgreSQL containers and
the dedicated Toxiproxy network. It must not interact with the persistent
`stormops-local` cluster or the PostgreSQL instance on port 15432.

The existing CI also runs the isolated `kind` E2E against a newly created
cluster. That test is separately scoped and is not part of the Maven suite.

## Evidence collection

1. Record the commit SHA, workflow run and test matrix (Java 21/25).
2. Keep sanitized JUnit/Failsafe summaries and relevant workflow logs.
3. For a network outage, verify both the failed proxied request and a
   successful diagnostic request to the database through its own disposable port.
4. After restoration, compare `active_run`, `stopped`, audit sequence
   and experiment result IDs. Do not replace missing evidence with assumptions.
5. Record remaining uncertainty separately from test assertions.

Do not publish passwords, Basic Auth headers, JWTs, kubeconfigs, internal
credential files, PostgreSQL connection passwords or live environment dumps.

## Manual reconciliation policy

1. Stop all API workers and confirm that no in-flight fault request, JVM or
   Kubernetes subprocess can still perform the previous operation.
2. Engage the persistent stop if the database is reachable. Otherwise keep
   every injector process stopped until the database can be inspected.
3. Record the exact `active_run` ID, all related audit events and missing
   terminal evidence. Treat an unpaired `INJECTION_INTENT` as ambiguous.
4. Inspect current Kubernetes pods, deployment readiness, request baselines
   and fresh Prometheus metrics. Observing recovery is not proof of the
   original fault's success or outcome.
5. Back up the live database through an explicitly reviewed operator procedure
   **before** any reconciliation. Do not use the test-only backup script on it.
6. Following the existing V2 recovery runbook, a reviewer may record an
   `INTERRUPTED` outcome and `RECOVERY_REVIEWED` event in an approved
   transaction for that exact run, then clear only its reservation while
   keeping `stopped=true`.
7. Perform an authenticated dry-run and independently authorize any future
   live experiment. Never automatically repeat a fault.

See [V2 safety and offline recovery](V2_SAFETY.md) for the existing
contract. No online force-unlock endpoint is permitted.

## Limits of these results

- Tests use isolated local networking and do not prove multi-host network
  resilience, cluster-wide partitions or production failover behavior.
- The JVM crash test proves fail-closed admission after restart, not recovery
  of a real Kubernetes fault whose outcome is unknown.
- PostgreSQL logical backup/restore does not prove point-in-time recovery,
  off-site storage, encrypted backup handling or a production RPO/RTO.
- No test establishes perfect detection of transient faults or guarantees
  distributed correctness under every possible concurrency interleaving.
- The application does not provide automatic recovery, fencing, active-active
  distributed coordination or production incident certification.

Document these distinctions in any portfolio demonstration.
