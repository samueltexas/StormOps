# Contributing to StormOps

Thank you for your interest in StormOps. This repository is a local-first engineering portfolio and learning project.

## Before contributing

- Read `README.md`, `docs/architecture/ARCHITECTURE.md`, `docs/SECURITY.md`, and `docs/KNOWN_LIMITATIONS.md`.
- Open an issue to discuss significant feature changes before implementing them.
- Do not propose changes that make production-cluster targeting the default.
- Never commit secrets, kubeconfigs, credentials, or private environment files.

## Development workflow

1. Fork the repository or create a feature branch.
2. Keep changes focused and document any behavioral or configuration changes.
3. Add or update tests for bug fixes and new behavior.
4. Run backend and frontend checks described in `docs/TESTING.md`.
5. Ensure `git diff --check` passes.
6. Open a pull request describing the motivation, changes, validation, and safety impact.

## Pull request checklist

- [ ] Changes are limited to the stated scope.
- [ ] Relevant automated tests pass.
- [ ] No secrets or sensitive configuration are included.
- [ ] Any Kubernetes-impacting behavior remains explicitly scoped and guarded.
- [ ] Documentation reflects the actual implementation.
- [ ] Known limitations are updated if appropriate.

## Reporting security issues

Follow `docs/SECURITY.md`. Do not disclose exploitable vulnerabilities or credentials in public issues.

## Licensing

Check the root `LICENSE` file and repository metadata for the actual licensing terms. Do not assume an open-source license has been granted merely because the repository is public.
