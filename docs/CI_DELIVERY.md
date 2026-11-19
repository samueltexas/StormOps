# CI, container delivery and Kubernetes identity

## Java compatibility contract

Both Maven projects explicitly set `maven.compiler.release=21`. Java 21 is the runtime baseline; Java 25 is also supported. The CI matrix compiles and tests the API and demo on JDK 21 and 25, and runs PostgreSQL integration tests on both. The runtime images use Java 21. The kind end-to-end job runs the JDK 21-built artifacts on JDK 21. This tests compatibility instead of silently treating the CI JDK as the target language level.

## Backend Kubernetes credentials

The backend uses `system:serviceaccount:stormops-lab:stormops-runner`, with the namespaced Role and RoleBinding in `infrastructure/kubernetes/chaos-rbac.yaml`. It needs:

- `pods`: `get`, `list`, `delete` in `stormops-lab`. `get` is necessary for UID preconditions and confirming deletion.
- `deployments`: `get` only for `stormops-demo`, to check desired and ready replicas.

It has no cluster role binding, secrets access, pod creation, deployment mutation or cross-namespace pod deletion. The application restricts labels separately because native RBAC cannot constrain deletion by label. The namespace must remain dedicated to the lab.

The host backend does **not** automatically use an in-cluster ServiceAccount mount. It uses an explicit kubeconfig at `.kube/stormops-runner-config`, overridable through `STORMOPS_KUBECONFIG`; it never falls back to ambient `KUBECONFIG` or the administrator's default file. The kubeconfig must contain a short-lived token for the dedicated account, cluster CA verification, and the fixed context name `kind-stormops-local`. Existing local credentials have not been migrated automatically. Supplying an administrator kubeconfig under this filename is not an acceptable setup; the operator owns credential provisioning and rotation. The image includes kubectl but no credentials.

For an approved lab, apply the RBAC manifest using a provisioning identity, issue a bounded token with the Kubernetes TokenRequest API (`kubectl create token stormops-runner --namespace stormops-lab --duration=1h`), and store it in a mode-600 runner kubeconfig without printing it in logs. Do not create legacy permanent ServiceAccount token Secrets. The E2E harness implements this process without exposing the token. It asserts the actual authenticated username and positive/negative authorization checks using that token, not administrator impersonation.

## Real fault proof in CI

`scripts/ci/kind_e2e.py` creates a uniquely named disposable kind cluster and PostgreSQL container. It refuses occupied loopback ports and never selects an existing cluster or the user's kubeconfig. NodePorts mapped to loopback preserve a stable demo endpoint while its pod is replaced; a port-forward tied to the deleted pod would invalidate the observation.

The test:

1. Installs the dedicated ServiceAccount and checks least-privilege access, including denied secrets reads, pod creation, deployment patching, node reads and deletion in another namespace.
2. Builds and loads the demo image, starts two replicas and real Prometheus, and generates baseline traffic.
3. Starts the actual authenticated API against isolated PostgreSQL and runner credentials.
4. Proves anonymous/viewer restrictions, the initial stop, and dry-run without changing pod UIDs.
5. Resumes, requests one real pod termination through the API and requires `RECOVERED`.
6. Confirms exactly one UID disappeared, exactly one replacement appeared and two pods remain; verifies precheck, intent, postcheck and outcome audit events with the authenticated actor.
7. Restarts the API, verifies persisted history, re-engages stop and proves a further injection is rejected.
8. Removes only its uniquely named cluster, database container and demo image.

Failures fail CI. Sanitized logs, RBAC assertions, pod UIDs, experiment output and audit records are retained as Actions artifacts for seven days. Kubeconfigs and tokens live only in a temporary directory and are never uploaded. CI runner teardown provides an additional cleanup boundary if the job is forcibly terminated.

To run intentionally on a development machine, first build both Maven projects, ensure Docker/kind/kubectl/Java and free loopback ports 18081/19090, then execute `python3 scripts/ci/kind_e2e.py`. This command deliberately disrupts one pod **in its own newly created test cluster**. It does not exercise or clean up the established lab.

## Delivery without automatic deployment

All pushes and pull requests run the test matrix, frontend tests/build, kind proof and container build checks. Version tag pushes matching `vMAJOR.MINOR.PATCH` (optionally a prerelease suffix) additionally publish these images only after every gate passes:

- `ghcr.io/samueltexas/stormops-api:<version-tag>`
- `ghcr.io/samueltexas/stormops-demo:<version-tag>`

Each also receives a full commit-SHA tag. The job summary records immutable image digests; use digest references when reproducing a release. Only the publish job receives `packages: write`. It uses the ephemeral `GITHUB_TOKEN`, not a personal access token. Pull requests cannot publish. Docker build contexts allow only Dockerfiles and built JARs, excluding local secrets and kubeconfigs.

This is container delivery, not automatic deployment. It does not create a cluster outside the disposable test or deploy to production. After publishing both images, a separate tag-only job creates a GitHub Release with the immutable image references and MIT notice. Only that job receives `contents: write`; reruns preserve existing release notes. No image or release exists merely because workflow files were added locally: publishing requires the reviewed changes and an explicitly pushed version tag. A release operator should review the code and prepare the version before pushing a release tag. The backend and demo service both use `2.0.0`. Tag publication validates that both Maven versions match the tag and that version-specific release notes exist.

The repository is public and the workflow uses standard GitHub-hosted Linux runners, with short artifact retention and no paid hosting. No paid infrastructure is provisioned. Reassess GitHub billing before changing repository visibility, runner class or retention.

The image binds to loopback by default, just like the JAR. A container deployment needs explicit endpoint/network configuration and a mounted least-privilege kubeconfig; no production-ready deployment is claimed. The current demo and telemetry endpoints are loopback-only and require the reviewed local networking setup.

## References

- [Kubernetes ServiceAccounts](https://kubernetes.io/docs/concepts/security/service-accounts/)
- [kind configuration and NodePort mappings](https://kind.sigs.k8s.io/docs/user/configuration/)
- [GitHub container publishing](https://docs.github.com/en/actions/tutorials/publish-packages/publish-docker-images)
