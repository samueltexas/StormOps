#!/bin/bash
set -Eeuo pipefail

# Todos os ficheiros e ferramentas ficam dentro do StormOps.
# Isto evita modificar instalações globais ou outros projetos.
PROJECT="$HOME/Projects/StormOps"
BIN="$PROJECT/.tools/bin"
DOWNLOADS="$PROJECT/.tools/downloads"

trap 'echo "ERRO na linha $LINENO. A instalação foi interrompida; os ficheiros existentes foram preservados."' ERR

echo "===== STORMOPS SETUP ====="

if [[ "$(uname -s)" != "Darwin" || "$(uname -m)" != "arm64" ]]; then
  echo "Este instalador requer macOS com Apple Silicon."
  exit 1
fi

for tool in curl python3 shasum tar git docker; do
  command -v "$tool" >/dev/null || {
    echo "Dependência em falta: $tool"
    exit 1
  }
done

mkdir -p "$BIN" "$DOWNLOADS"

# Consultar versões oficiais em vez de assumir versões antigas.
get_latest_release() {
  curl -fsSL --retry 3 \
    "https://api.github.com/repos/$1/releases/latest" |
    python3 -c 'import json,sys; print(json.load(sys.stdin)["tag_name"])'
}

verify_checksum() {
  local file="$1"
  local checksum_file="$2"
  local expected actual

  expected="$(awk '{print $1; exit}' "$checksum_file")"
  actual="$(shasum -a 256 "$file" | awk '{print $1}')"

  if [[ ! "$expected" =~ ^[0-9a-fA-F]{64}$ ]] ||
     [[ "$(printf '%s' "$expected" | tr '[:upper:]' '[:lower:]')" != "$(printf '%s' "$actual" | tr '[:upper:]' '[:lower:]')" ]]; then
    echo "Falha na verificação SHA-256: $file"
    return 1
  fi
}

echo ""
echo "[1/5] Instalar kind"

if [[ ! -x "$BIN/kind" ]]; then
  KIND_VERSION="$(get_latest_release kubernetes-sigs/kind)"
  KIND_URL="https://github.com/kubernetes-sigs/kind/releases/download/${KIND_VERSION}/kind-darwin-arm64"

  echo "Versão: $KIND_VERSION"

  curl -fL --retry 3 "$KIND_URL" \
    -o "$DOWNLOADS/kind-darwin-arm64"

  curl -fL --retry 3 "${KIND_URL}.sha256sum" \
    -o "$DOWNLOADS/kind.sha256sum"

  verify_checksum \
    "$DOWNLOADS/kind-darwin-arm64" \
    "$DOWNLOADS/kind.sha256sum"

  cp "$DOWNLOADS/kind-darwin-arm64" "$BIN/kind"
  chmod 755 "$BIN/kind"
fi

"$BIN/kind" version

echo ""
echo "[2/5] Instalar Helm"

if [[ ! -x "$BIN/helm" ]]; then
  HELM_VERSION="$(get_latest_release helm/helm)"
  HELM_ARCHIVE="helm-${HELM_VERSION}-darwin-arm64.tar.gz"
  HELM_URL="https://get.helm.sh/${HELM_ARCHIVE}"

  echo "Versão: $HELM_VERSION"

  curl -fL --retry 3 "$HELM_URL" \
    -o "$DOWNLOADS/$HELM_ARCHIVE"

  curl -fL --retry 3 "${HELM_URL}.sha256sum" \
    -o "$DOWNLOADS/helm.sha256sum"

  verify_checksum \
    "$DOWNLOADS/$HELM_ARCHIVE" \
    "$DOWNLOADS/helm.sha256sum"

  tar -xzf "$DOWNLOADS/$HELM_ARCHIVE" \
    -C "$DOWNLOADS" darwin-arm64/helm

  cp "$DOWNLOADS/darwin-arm64/helm" "$BIN/helm"
  chmod 755 "$BIN/helm"
fi

"$BIN/helm" version --short

echo ""
echo "[3/5] Criar estrutura do projeto"

mkdir -p \
  "$PROJECT/backend" \
  "$PROJECT/frontend" \
  "$PROJECT/demo-services/sample-java-service" \
  "$PROJECT/infrastructure/docker" \
  "$PROJECT/infrastructure/kubernetes" \
  "$PROJECT/infrastructure/helm" \
  "$PROJECT/infrastructure/prometheus" \
  "$PROJECT/infrastructure/grafana" \
  "$PROJECT/docs/architecture/decisions" \
  "$PROJECT/docs/experiments" \
  "$PROJECT/docs/screenshots" \
  "$PROJECT/.github/workflows"

# Não substituir ficheiros existentes numa repetição.
if [[ ! -f "$PROJECT/.gitignore" ]]; then
  cat > "$PROJECT/.gitignore" <<'EOF'
.tools/
target/
node_modules/
dist/
build/
.env
.env.*
!.env.example
*.log
*.tmp
*.pem
*.key
.DS_Store
.idea/
.vscode/
.kube/
*.kubeconfig
EOF
fi

if [[ ! -f "$PROJECT/README.md" ]]; then
  cat > "$PROJECT/README.md" <<'EOF'
# StormOps

A Java-based Chaos Engineering and Resilience Platform.

## Stack

Java, Spring Boot, React, TypeScript, PostgreSQL,
Docker, Kubernetes, Prometheus and Grafana.

## Goals

- Run controlled fault-injection experiments.
- Observe system behavior and recovery.
- Measure availability, latency and error rates.
- Produce experiment reports and audit records.
- Protect non-laboratory workloads.

## Development

Local-first, with no mandatory paid services.

## Status

Phase 1: Environment preparation.
EOF
fi

echo ""
echo "[4/5] Inicializar Git"

cd "$PROJECT"

if [[ ! -d .git ]]; then
  git init -b main
fi

echo ""
echo "[5/5] Validar ferramentas"

"$BIN/kind" version
"$BIN/helm" version --short
java -version 2>&1 | head -n 1
docker --version
kubectl version --client

echo ""
echo "Diretório do projeto:"
pwd

echo ""
echo "Estado do Git:"
git status --short

echo ""
echo "Containers existentes:"
docker ps -a --format 'table {{.Names}}\t{{.Status}}'

echo ""
echo "===== STORMOPS SETUP COMPLETED ====="
echo "Projeto: $PROJECT"
echo "kind: $BIN/kind"
echo "Helm: $BIN/helm"
