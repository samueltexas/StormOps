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
