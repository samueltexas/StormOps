# StormOps — Phase 4: Local Experiment API

## Scope

This package adds a Spring Boot control-plane API, a Kubernetes adapter with a fixed lab scope, and a controlled pod-termination experiment. It does not modify existing demo-service or monitoring manifests.

## Endpoints

- `GET http://127.0.0.1:18080/actuator/health`
- `GET http://127.0.0.1:18080/api/v1/experiments`
- `POST http://127.0.0.1:18080/api/v1/experiments/pod-termination`

The POST request requires the `X-StormOps-Confirmation: TERMINATE_STORMOPS_DEMO_POD` header.

## Known limitations

History is in memory; pod readiness is not an availability SLO; only the dedicated local demo deployment is supported. The API binds to loopback and is not suitable for public exposure.
