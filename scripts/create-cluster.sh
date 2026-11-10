#!/bin/bash
set -Eeuo pipefail

# Este script gere exclusivamente o cluster stormops-local.
# O kubeconfig fica dentro do projeto para evitar alterações
# no contexto Kubernetes global do computador.

PROJECT="$HOME/Projects/StormOps"
KIND="$PROJECT/.tools/bin/kind"
KUBECONFIG_FILE="$PROJECT/.kube/stormops-config"
CLUSTER_NAME="stormops-local"
CONFIG="$PROJECT/infrastructure/kubernetes/kind-config.yaml"

trap 'echo "ERRO: operação interrompida na linha $LINENO."' ERR

echo "===== STORMOPS KUBERNETES ====="

if [[ ! -x "$KIND" ]]; then
  echo "ERRO: kind não encontrado."
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "ERRO: Docker Desktop não está operacional."
  exit 1
fi

mkdir -p "$PROJECT/.kube"
mkdir -p "$PROJECT/infrastructure/kubernetes"

# O API Server fica acessível apenas no próprio Mac.
# A porta 0 permite ao kind selecionar uma porta disponível,
# evitando conflitos com os serviços ViajarPeloMundo.
if [[ ! -f "$CONFIG" ]]; then
  cat > "$CONFIG" <<'EOF'
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
name: stormops-local
nodes:
  - role: control-plane
networking:
  apiServerAddress: "127.0.0.1"
  apiServerPort: 0
EOF
fi

echo ""
echo "[1/5] Verificar clusters existentes"

CLUSTERS="$("$KIND" get clusters)"

if printf '%s\n' "$CLUSTERS" | grep -Fxq "$CLUSTER_NAME"; then
  echo "O cluster StormOps já existe."
  echo "Não será recriado."
else
  echo "Criando cluster independente..."

  "$KIND" create cluster \
    --name "$CLUSTER_NAME" \
    --config "$CONFIG" \
    --kubeconfig "$KUBECONFIG_FILE" \
    --wait 180s
fi

# Recuperar a configuração exclusiva do cluster caso
# este já existisse de uma execução anterior.
"$KIND" export kubeconfig \
  --name "$CLUSTER_NAME" \
  --kubeconfig "$KUBECONFIG_FILE"

chmod 600 "$KUBECONFIG_FILE"

export KUBECONFIG="$KUBECONFIG_FILE"

echo ""
echo "[2/5] Confirmar ligação Kubernetes"

kubectl --context "kind-$CLUSTER_NAME" cluster-info

echo ""
echo "[3/5] Criar namespaces"

kubectl --context "kind-$CLUSTER_NAME" \
  create namespace stormops-lab \
  --dry-run=client -o yaml |
  kubectl --context "kind-$CLUSTER_NAME" apply -f -

kubectl --context "kind-$CLUSTER_NAME" \
  create namespace stormops-monitoring \
  --dry-run=client -o yaml |
  kubectl --context "kind-$CLUSTER_NAME" apply -f -

echo ""
echo "[4/5] Criar limites para o laboratório"

# A quota reduz o risco de experiências consumirem
# recursos excessivos dentro do namespace de testes.
cat > "$PROJECT/infrastructure/kubernetes/lab-quota.yaml" <<'EOF'
apiVersion: v1
kind: ResourceQuota
metadata:
  name: stormops-lab-quota
  namespace: stormops-lab
spec:
  hard:
    requests.cpu: "2"
    requests.memory: 3Gi
    limits.cpu: "4"
    limits.memory: 4Gi
    pods: "12"
    services: "10"
EOF

kubectl --context "kind-$CLUSTER_NAME" \
  apply -f "$PROJECT/infrastructure/kubernetes/lab-quota.yaml"

echo ""
echo "[5/5] Validar cluster"

kubectl --context "kind-$CLUSTER_NAME" get nodes -o wide

kubectl --context "kind-$CLUSTER_NAME" get namespaces

kubectl --context "kind-$CLUSTER_NAME" \
  describe resourcequota stormops-lab-quota \
  -n stormops-lab

echo ""
echo "Containers Docker existentes:"
docker ps --format 'table {{.Names}}\t{{.Status}}'

echo ""
echo "===== STORMOPS CLUSTER READY ====="
echo "Cluster: $CLUSTER_NAME"
echo "Kubeconfig: $KUBECONFIG_FILE"
echo "Namespaces: stormops-lab, stormops-monitoring"
