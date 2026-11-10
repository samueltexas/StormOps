#!/bin/bash
set -Eeuo pipefail

PROJECT="$HOME/Projects/StormOps"
APP="$PROJECT/backend"

if ! docker info >/dev/null 2>&1; then
    echo "Docker Desktop is unavailable."
    exit 1
fi

export KUBECONFIG="$PROJECT/.kube/stormops-config"
kubectl --context kind-stormops-local get namespace stormops-lab >/dev/null

cd "$APP"
exec ./mvnw spring-boot:run
