#!/bin/bash
set -Eeuo pipefail

# Monitorização local do StormOps.
# Mantemos os recursos reduzidos porque Docker Desktop
# também executa os outros projetos do computador.

PROJECT="$HOME/Projects/StormOps"
KUBECONFIG="$PROJECT/.kube/stormops-config"
CONTEXT="kind-stormops-local"
DIR="$PROJECT/infrastructure/kubernetes/monitoring"

export KUBECONFIG

trap 'echo "ERRO na linha $LINENO. Os recursos existentes foram preservados."' ERR

echo "===== STORMOPS MONITORING SETUP ====="

docker info >/dev/null
kubectl --context "$CONTEXT" get namespace stormops-monitoring >/dev/null
kubectl --context "$CONTEXT" get service stormops-demo -n stormops-lab >/dev/null

mkdir -p "$DIR"

echo "[1/5] Configurar Prometheus"

cat > "$DIR/prometheus.yaml" <<'EOF'
apiVersion: v1
kind: ConfigMap
metadata:
  name: stormops-prometheus-config
  namespace: stormops-monitoring
data:
  prometheus.yml: |
    global:
      scrape_interval: 15s
      evaluation_interval: 15s

    scrape_configs:
      - job_name: stormops-demo
        metrics_path: /actuator/prometheus
        static_configs:
          - targets:
              - stormops-demo.stormops-lab.svc.cluster.local:8080
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: stormops-prometheus
  namespace: stormops-monitoring
spec:
  replicas: 1
  strategy:
    type: Recreate
  selector:
    matchLabels:
      app: stormops-prometheus
  template:
    metadata:
      labels:
        app: stormops-prometheus
    spec:
      containers:
        - name: prometheus
          image: prom/prometheus:v3.5.0
          args:
            - --config.file=/etc/prometheus/prometheus.yml
            - --storage.tsdb.path=/prometheus
            - --storage.tsdb.retention.time=24h
            - --storage.tsdb.retention.size=256MB
            - --web.enable-lifecycle
          ports:
            - containerPort: 9090
              name: http
          resources:
            requests:
              cpu: 100m
              memory: 192Mi
            limits:
              cpu: 500m
              memory: 512Mi
          readinessProbe:
            httpGet:
              path: /-/ready
              port: http
            periodSeconds: 5
          volumeMounts:
            - name: config
              mountPath: /etc/prometheus
            - name: data
              mountPath: /prometheus
      volumes:
        - name: config
          configMap:
            name: stormops-prometheus-config
        - name: data
          emptyDir:
            sizeLimit: 300Mi
---
apiVersion: v1
kind: Service
metadata:
  name: stormops-prometheus
  namespace: stormops-monitoring
spec:
  selector:
    app: stormops-prometheus
  ports:
    - name: http
      port: 9090
      targetPort: 9090
EOF

echo "[2/5] Configurar Grafana"

cat > "$DIR/grafana.yaml" <<'EOF'
apiVersion: v1
kind: ConfigMap
metadata:
  name: stormops-grafana-provisioning
  namespace: stormops-monitoring
data:
  datasource.yaml: |
    apiVersion: 1
    datasources:
      - name: StormOps Prometheus
        uid: stormops-prometheus
        type: prometheus
        access: proxy
        url: http://stormops-prometheus.stormops-monitoring.svc.cluster.local:9090
        isDefault: true
        editable: false
  dashboards.yaml: |
    apiVersion: 1
    providers:
      - name: StormOps
        orgId: 1
        folder: StormOps
        type: file
        disableDeletion: true
        options:
          path: /var/lib/grafana/dashboards
  stormops.json: |
    {
      "uid": "stormops-overview",
      "title": "StormOps - Resilience Overview",
      "tags": ["stormops", "java", "kubernetes"],
      "timezone": "browser",
      "schemaVersion": 41,
      "refresh": "10s",
      "time": {"from": "now-15m", "to": "now"},
      "panels": [
        {
          "id": 1,
          "type": "stat",
          "title": "Demo Service Available",
          "gridPos": {"x": 0, "y": 0, "w": 8, "h": 5},
          "datasource": {"type": "prometheus", "uid": "stormops-prometheus"},
          "targets": [
            {"refId": "A", "expr": "up{job=\"stormops-demo\"}"}
          ]
        },
        {
          "id": 2,
          "type": "timeseries",
          "title": "HTTP Requests per Second",
          "gridPos": {"x": 8, "y": 0, "w": 16, "h": 8},
          "datasource": {"type": "prometheus", "uid": "stormops-prometheus"},
          "targets": [
            {
              "refId": "A",
              "expr": "sum(rate(http_server_requests_seconds_count{application=\"stormops-demo\"}[1m]))"
            }
          ]
        },
        {
          "id": 3,
          "type": "timeseries",
          "title": "JVM Heap Memory",
          "gridPos": {"x": 0, "y": 8, "w": 12, "h": 8},
          "datasource": {"type": "prometheus", "uid": "stormops-prometheus"},
          "targets": [
            {
              "refId": "A",
              "expr": "jvm_memory_used_bytes{area=\"heap\"}"
            }
          ]
        },
        {
          "id": 4,
          "type": "timeseries",
          "title": "HTTP Error Rate",
          "gridPos": {"x": 12, "y": 8, "w": 12, "h": 8},
          "datasource": {"type": "prometheus", "uid": "stormops-prometheus"},
          "targets": [
            {
              "refId": "A",
              "expr": "sum(rate(http_server_requests_seconds_count{status=~\"5..\"}[1m])) or vector(0)"
            }
          ]
        }
      ]
    }
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: stormops-grafana
  namespace: stormops-monitoring
spec:
  replicas: 1
  strategy:
    type: Recreate
  selector:
    matchLabels:
      app: stormops-grafana
  template:
    metadata:
      labels:
        app: stormops-grafana
    spec:
      containers:
        - name: grafana
          image: grafana/grafana:12.1.0
          ports:
            - containerPort: 3000
              name: http
          env:
            - name: GF_AUTH_ANONYMOUS_ENABLED
              value: "false"
            - name: GF_SECURITY_ADMIN_USER
              value: admin
            - name: GF_SECURITY_ADMIN_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: stormops-grafana-admin
                  key: password
            - name: GF_USERS_ALLOW_SIGN_UP
              value: "false"
          resources:
            requests:
              cpu: 100m
              memory: 128Mi
            limits:
              cpu: 500m
              memory: 384Mi
          readinessProbe:
            httpGet:
              path: /api/health
              port: http
            periodSeconds: 5
          volumeMounts:
            - name: provisioning
              mountPath: /etc/grafana/provisioning/datasources/datasource.yaml
              subPath: datasource.yaml
            - name: provisioning
              mountPath: /etc/grafana/provisioning/dashboards/dashboards.yaml
              subPath: dashboards.yaml
            - name: provisioning
              mountPath: /var/lib/grafana/dashboards/stormops.json
              subPath: stormops.json
      volumes:
        - name: provisioning
          configMap:
            name: stormops-grafana-provisioning
---
apiVersion: v1
kind: Service
metadata:
  name: stormops-grafana
  namespace: stormops-monitoring
spec:
  selector:
    app: stormops-grafana
  ports:
    - name: http
      port: 3000
      targetPort: 3000
EOF

echo "[3/5] Criar credenciais locais do Grafana"

# Gerar a senha apenas na primeira instalação.
# O segredo fica no Kubernetes, não no Git.
if ! kubectl --context "$CONTEXT" \
  get secret stormops-grafana-admin \
  -n stormops-monitoring >/dev/null 2>&1; then

  PASSWORD="$(openssl rand -hex 18)"

  kubectl --context "$CONTEXT" \
    create secret generic stormops-grafana-admin \
    -n stormops-monitoring \
    --from-literal=password="$PASSWORD"
fi

echo "[4/5] Instalar componentes"

kubectl --context "$CONTEXT" apply -f "$DIR/prometheus.yaml"
kubectl --context "$CONTEXT" apply -f "$DIR/grafana.yaml"

kubectl --context "$CONTEXT" \
  rollout status deployment/stormops-prometheus \
  -n stormops-monitoring --timeout=240s

kubectl --context "$CONTEXT" \
  rollout status deployment/stormops-grafana \
  -n stormops-monitoring --timeout=240s

echo "[5/5] Validar serviços"

kubectl --context "$CONTEXT" \
  get pods,services,deployments \
  -n stormops-monitoring

echo ""
echo "===== MONITORING READY ====="
echo "Prometheus e Grafana instalados."
