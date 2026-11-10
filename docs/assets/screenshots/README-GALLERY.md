<!-- STORMOPS_SCREENSHOTS_START -->
## Product screenshots

Real screenshots from the local StormOps development environment. The application runs against an isolated kind Kubernetes lab; results shown are historical test data, not production service-level guarantees.

### Administrator overview
![StormOps administrator overview with experiment outcomes and Kubernetes lab](docs/assets/screenshots/overview-admin.png)

### Experiment history
![StormOps experiment history with pod termination and HTTP simulation results](docs/assets/screenshots/experiments.png)

### Resilience analytics
![StormOps analytics showing experiment counts and distinct pod and HTTP timing metrics](docs/assets/screenshots/analytics.png)

### Reliability policy evaluation
![StormOps reliability evaluation with configurable thresholds and historical results](docs/assets/screenshots/reliability.png)

### Observability integration
![StormOps observability page with Grafana link and backend health status](docs/assets/screenshots/observability.png)

### Read-only viewer experience
![StormOps viewer role overview without experiment execution action](docs/assets/screenshots/overview-viewer.png)

> **Security and operations:** The viewer screenshot illustrates UI-level restrictions; authorization is enforced separately by the backend. Grafana availability depends on the local monitoring stack. See [Security](docs/SECURITY.md), [Testing](docs/TESTING.md), and [Known limitations](docs/KNOWN_LIMITATIONS.md).
<!-- STORMOPS_SCREENSHOTS_END -->
