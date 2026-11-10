#!/bin/bash
set -Eeuo pipefail

PROJECT="${STORMOPS_PROJECT:-$HOME/Projects/StormOps}"
ENV_FILE="$PROJECT/.secrets/stormops-db.env"

if [[ ! -f "$ENV_FILE" ]]; then
    echo "Missing StormOps database environment: $ENV_FILE" >&2
    exit 1
fi

set -a
# The file is generated locally with restrictive permissions.
source "$ENV_FILE"
set +a

export KUBECONFIG="$PROJECT/.kube/stormops-config"
cd "$PROJECT/backend"
exec ./mvnw -B spring-boot:run
