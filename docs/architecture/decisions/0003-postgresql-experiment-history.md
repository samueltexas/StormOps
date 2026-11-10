# ADR 0003: Persist experiment outcomes in a dedicated PostgreSQL database

Status: Accepted

## Context

Phase 4 retained experiment outcomes in process memory. Restarting the API erased history and prevented reliable audit and reporting.

## Decision

Use PostgreSQL 17 in a dedicated Docker Compose project with a private loopback port, isolated named volume, and application-specific credentials. Flyway owns schema migrations. Spring JDBC uses parameterized SQL and maps database rows to immutable domain records. Keep the existing list endpoint contract, adding paginated and by-ID reads.

## Consequences

History survives API restarts and database container recreation, provided the named volume is retained. The database must be healthy before the API starts. The initial migration stores completed outcomes only; there is not yet a durable RUNNING state or distributed experiment lock. If persistence fails after a pod is deleted, the request fails and an audit gap is possible. This is acceptable only for the local MVP and must be addressed before production deployment.

The local API is bound to loopback, but there is no user authentication yet. Never expose the API to untrusted networks.
