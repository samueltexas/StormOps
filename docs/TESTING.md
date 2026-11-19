# Testing and Continuous Integration

## Testing strategy

StormOps uses layered automated checks to validate domain behavior, API security, database persistence, and frontend functionality.

| Layer | Coverage described in the repository | Infrastructure |
| --- | --- | --- |
| Backend unit tests | Experiment service behavior, security configuration, controller behavior | JVM / Maven |
| Backend integration tests | PostgreSQL/Flyway, repository persistence, API role restrictions, analytics and reliability endpoints | Testcontainers and PostgreSQL |
| Frontend tests | React/TypeScript application checks | Node.js / Vitest |
| Frontend build | TypeScript and production asset compilation | Node.js / Vite |

## Verified integration scenarios

`backend/src/test/java/com/stormops/api/integration/StormOpsIntegrationIT.java` includes tests named:

- `flywayCreatesExpectedSchema`
- `repositoryPersistsAndReadsWithoutTouchingProduction`
- `anonymousCannotReadProtectedApi`
- `viewerCanReadButCannotExecuteEitherFault`
- `adminCanReadAndInvalidConfirmationDoesNotExecuteFault`
- `analyticsAndReliabilityReturnValidReports`

These tests use an isolated PostgreSQL Testcontainers instance rather than the normal local application database. The authorization tests are designed not to trigger destructive Kubernetes operations.

## Run locally

From the repository root, with Docker Desktop available for integration tests:

```bash
cd "$HOME/Projects/StormOps"
cd backend
./mvnw test
./mvnw -Pintegration-tests verify
```

Frontend commands:

```bash
cd "$HOME/Projects/StormOps/frontend"
npm ci
npm test -- --run
npm run build
```

Consult the exact Maven and npm scripts in the checked-out repository if the project configuration changes. The `integration-tests` profile activates Failsafe. Run `-Pintegration-tests verify` to include the disposable database backup/restore integration tests; plain `verify` does not activate these tests.

## CI

GitHub Actions workflow: `.github/workflows/ci.yml`.

The workflow runs backend unit tests, backend integration tests, and frontend tests/build. CI should be green before merging changes to `main`. A successful CI run confirms the configured checks, not end-to-end production readiness.

## Manual validation checklist

- Confirm the API is bound to loopback and security is enabled.
- Confirm anonymous requests to protected endpoints are rejected.
- Confirm VIEWER can read permitted resources but cannot execute experiments.
- Confirm ADMIN cannot execute experiments with invalid confirmation.
- Confirm database migration and experiment-history queries work.
- Confirm Kubernetes context and namespace before any deliberate chaos experiment.
- Confirm Grafana/Prometheus are reachable only as intended for the local lab.

## Current coverage gaps

The project does not claim exhaustive browser E2E coverage, penetration testing, failure-injection testing against arbitrary Kubernetes clusters, or distributed-lock correctness. Expand test coverage before supporting multi-instance deployments or untrusted users.

## V3 operational failure testing

See [V3 operational failure testing](V3_OPERATIONAL_FAILURE_TESTING.md) for the
safety failure matrix and the distinction between mock-based coverage and
pending disposable E2E scenarios. No existing local cluster or database is
modified by the new tests.

## v2 safety regression suite

Run `./mvnw --offline --batch-mode --no-transfer-progress -Pintegration-tests verify` from `backend` when dependencies are cached. Testcontainers uses a disposable PostgreSQL database and never the local StormOps database. Unit tests mock external fault operations. Coverage includes concurrent admission, retained reservations after failed completion, fresh telemetry, fixed pod eligibility, dry-run, stop checks and default HTTP authentication. No Kubernetes experiment is run by this suite.

## V3 evidence and network-partition regression

The integration profile now also tests PostgreSQL TCP availability with an isolated
Toxiproxy container and Testcontainers network. For a reviewed failure matrix,
reproduction commands and recovery limits, see
[V3 recovery evidence](V3_RECOVERY_EVIDENCE.md).

## Real Kubernetes E2E

The CI kind job intentionally runs a real fault against its own disposable cluster. This is separate from the mock-based unit and Testcontainers integration suites. See [CI and delivery](CI_DELIVERY.md) for the exact assertions, local invocation and cleanup boundary.
