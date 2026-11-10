#!/bin/bash
set -Eeuo pipefail

# Recuperar a ligação ao cluster existente sem o eliminar.
# O kubeconfig continua isolado dentro do StormOps.
PROJECT="$HOME/Projects/StormOps"
KIND="$PROJECT/.tools/bin/kind"
KUBECONFIG_FILE="$PROJECT/.kube/stormops-config"
CLUSTER="stormops-local"
CONTEXT="kind-stormops-local"
NODE="stormops-local-control-plane"

trap 'echo "ERRO na linha $LINENO. Nenhum cluster foi eliminado."' ERR

echo "===== STORMOPS CLUSTER RECOVERY ====="

echo ""
echo "[1/5] Verificar Docker Desktop"

if ! docker info >/dev/null 2>&1; then
  echo "ERRO: Docker Desktop não está operacional."
  echo "Abre o Docker Desktop e aguarda até ficar pronto."
  exit 1
fi

echo "Docker operacional."

echo ""
echo "[2/5] Verificar cluster existente"

if ! "$KIND" get clusters | grep -Fxq "$CLUSTER"; then
  echo "ERRO: O cluster StormOps não foi encontrado."
  echo "Não será criado automaticamente para evitar alterações inesperadas."
  exit 1
fi

echo "Cluster encontrado: $CLUSTER"

echo ""
echo "[3/5] Verificar container Kubernetes"

STATE="$(docker inspect -f '{{.State.Status}}' "$NODE")"

echo "Estado: $STATE"

if [[ "$STATE" != "running" ]]; then
  echo "O container Kubernetes está parado."
  echo "A iniciar exclusivamente o container StormOps..."
  docker start "$NODE"
fi

echo ""
echo "[4/5] Atualizar kubeconfig"

mkdir -p "$PROJECT/.kube"

"$KIND" export kubeconfig \
  --name "$CLUSTER" \
  --kubeconfig "$KUBECONFIG_FILE"

chmod 600 "$KUBECONFIG_FILE"

export KUBECONFIG="$KUBECONFIG_FILE"

echo ""
echo "[5/5] Aguardar Kubernetes"

READY=0

for attempt in $(seq 1 24); do
  if kubectl --context "$CONTEXT" \
    get --raw=/readyz >/dev/null 2>&1; then
    READY=1
    break
  fi

  echo "A aguardar API Kubernetes... tentativa $attempt/24"
  sleep 5
done

if [[ "$READY" -ne 1 ]]; then
  echo "ERRO: A API Kubernetes continua indisponível."
  echo "O cluster foi preservado para diagnóstico."
  exit 1
fi

kubectl --context "$CONTEXT" get nodes
kubectl --context "$CONTEXT" get namespaces

echo ""
echo "===== STORMOPS CLUSTER RECOVERED ====="
