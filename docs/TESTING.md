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
./mvnw verify
```

Frontend commands:

```bash
cd "$HOME/Projects/StormOps/frontend"
npm ci
npm test -- --run
npm run build
```

Consult the exact Maven and npm scripts in the checked-out repository if the project configuration changes. Maven `verify` is intended to include the integration-test phase configured through Failsafe.

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
