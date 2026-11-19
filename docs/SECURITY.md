# StormOps Security Policy

## Scope and security posture

StormOps is a **local-first chaos engineering laboratory**, not a production-hardened control plane. Run it only in a trusted local environment. Do not expose the API, frontend, PostgreSQL, Kubernetes API, or monitoring endpoints to untrusted networks without additional security engineering and review.

## Authentication and authorization

The backend uses Spring Security with HTTP Basic authentication by default. Local users are configured with environment variables; passwords are encoded using BCrypt in the in-memory user store. The application supports `ADMIN` and `VIEWER` roles.

| Action | ADMIN | VIEWER | Unauthenticated (security enabled) |
| --- | --- | --- | --- |
| Read protected API resources | Allowed | Allowed | Denied |
| Run pod termination | Allowed with explicit confirmation | Denied | Denied |
| Run HTTP simulation | Allowed with explicit confirmation | Denied | Denied |
| Health endpoint | Allowed | Allowed | Allowed |

The two experiment endpoints require an `X-StormOps-Confirmation` header with a specific value. This confirmation is an accidental-execution safeguard, **not an authentication mechanism**.

### Important default

Authentication defaults to enabled and missing passwords prevent startup. Disabling it requires both the `dev` profile and `STORMOPS_SECURITY_ENABLED=false`; all other profiles reject the opt-out. See [v2 safety](V2_SAFETY.md) for durable admission, audit, kill switch and offline crash recovery.

## Credential handling

- Keep `.secrets/stormops-security.env` local and excluded from version control.
- Restrict its file permissions to `600` and never paste credentials into issues, screenshots, logs, or pull requests.
- Use distinct ADMIN and VIEWER usernames and strong, unique passwords.
- Rotate credentials if exposure is suspected.
- Do not store credentials in the frontend repository or commit a precomputed HTTP Basic header.
- HTTP Basic transmits reusable credentials in encoded form, **not encrypted form**. Use it only on trusted loopback connections unless HTTPS is configured and verified.

## Kubernetes safety boundaries

The local chaos gateway targets the dedicated `kind-stormops-local` context, `stormops-lab` namespace, and `stormops-demo` deployment. Pod termination requires the expected healthy replica state and observes recovery for a bounded period. These controls reduce accidental impact but do not make the platform safe for arbitrary clusters.

Do not reuse a production kubeconfig or widen cluster permissions without a dedicated threat model, least-privilege RBAC, and operational review.

## Browser and API considerations

The backend currently disables CSRF protection for its HTTP Basic API. Reassess CSRF and browser credential behavior before enabling cookie/session authentication or exposing the service outside loopback. Browser HTTP Basic credential caching can also affect logout behavior.

## Known limitations

- An explicit insecure dev opt-out remains available for isolated development.
- Credentials are managed as local environment variables and in-memory users; there is no external identity provider or centralized secret manager.
- HTTP Basic does not provide token revocation or a full session-management system.
- Concurrency is limited by a durable singleton in the shared PostgreSQL database; independent databases do not coordinate.
- Interrupted runs retain their durable reservation and require offline operator reconciliation.
- An external action cannot be atomic with a database commit; persisted intent can have an unknown outcome. Failed completion preserves the reservation.
- The project has not undergone an independent penetration test or production security assessment.

## Security testing

The repository includes unit tests for role configuration and integration tests for protected API access, VIEWER restrictions, and invalid experiment confirmations. GitHub Actions runs automated backend and frontend checks. Passing tests is not a substitute for a security audit.

## Reporting a vulnerability

Please do not publish exploitable details or credentials in a public GitHub issue. Contact the repository maintainer privately through a verified contact method listed on the maintainer's GitHub profile. If no private contact method is available, request a private reporting channel without disclosing the vulnerability details.

## Deployment warning

**Do not deploy StormOps publicly in its current configuration.** Before any public deployment, require authentication by default, HTTPS, network isolation, least-privilege Kubernetes RBAC, secret management, request throttling, security logging, safe error responses, and a reviewed threat model.
