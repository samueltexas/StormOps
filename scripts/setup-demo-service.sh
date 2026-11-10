#!/bin/bash
set -Eeuo pipefail

# Criamos uma aplicação Java independente para funcionar
# como alvo seguro das futuras experiências StormOps.
# Nenhum serviço do ViajarPeloMundo será alterado.

PROJECT="$HOME/Projects/StormOps"
APP="$PROJECT/demo-services/sample-java-service"
KIND="$PROJECT/.tools/bin/kind"
KUBECONFIG="$PROJECT/.kube/stormops-config"
CONTEXT="kind-stormops-local"
MANIFEST="$PROJECT/infrastructure/kubernetes/demo-service.yaml"

export KUBECONFIG

trap 'echo "ERRO na linha $LINENO. Consulta a mensagem acima; os ficheiros foram preservados."' ERR

echo "===== STORMOPS JAVA DEMO SERVICE ====="

for tool in curl unzip java docker kubectl; do
  command -v "$tool" >/dev/null || {
    echo "Dependência em falta: $tool"
    exit 1
  }
done

if [[ ! -x "$KIND" || ! -f "$KUBECONFIG" ]]; then
  echo "ERRO: O cluster StormOps não está configurado."
  exit 1
fi

kubectl --context "$CONTEXT" get namespace stormops-lab >/dev/null

mkdir -p "$APP"
mkdir -p "$PROJECT/.tools/downloads"

echo ""
echo "[1/7] Gerar aplicação Spring Boot"

if [[ ! -f "$APP/pom.xml" ]]; then
  ARCHIVE="$PROJECT/.tools/downloads/stormops-demo.zip"

  # O Spring Initializr gera o projeto e o Maven Wrapper.
  # Java 21 é o nível de compilação escolhido; o JDK 25
  # instalado no Mac pode compilar código destinado a Java 21.
  curl -fLsS --retry 3 --get \
    "https://start.spring.io/starter.zip" \
    --data-urlencode "type=maven-project" \
    --data-urlencode "language=java" \
    --data-urlencode "groupId=com.stormops" \
    --data-urlencode "artifactId=sample-java-service" \
    --data-urlencode "name=sample-java-service" \
    --data-urlencode "packageName=com.stormops.demo" \
    --data-urlencode "javaVersion=21" \
    --data-urlencode "dependencies=web,actuator,prometheus" \
    -o "$ARCHIVE"

  unzip -tq "$ARCHIVE" >/dev/null
  unzip -nq "$ARCHIVE" -d "$APP"
fi

if [[ ! -f "$APP/pom.xml" || ! -f "$APP/mvnw" ]]; then
  echo "ERRO: Spring Initializr não gerou o projeto esperado."
  exit 1
fi

mkdir -p "$APP/src/main/java/com/stormops/demo"
mkdir -p "$APP/src/main/resources"
mkdir -p "$APP/src/test/java/com/stormops/demo"

echo ""
echo "[2/7] Criar endpoints Java"

cat > "$APP/src/main/java/com/stormops/demo/DemoController.java" <<'EOF'
package com.stormops.demo;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serviço de demonstração do StormOps.
 *
 * As respostas HTTP permitem medir disponibilidade e latência.
 * O contador utiliza AtomicLong para suportar pedidos simultâneos
 * sem perder incrementos entre diferentes threads.
 *
 * Cada réplica mantém o seu contador em memória. Por isso,
 * este valor não representa o total global do Kubernetes.
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    private final AtomicLong requestCounter = new AtomicLong();

    @GetMapping("/hello")
    public Map<String, Object> hello() {
        long count = requestCounter.incrementAndGet();

        return Map.of(
            "service", "stormops-demo",
            "message", "StormOps Java service is running",
            "requestCount", count,
            "timestamp", Instant.now().toString()
        );
    }

    /**
     * Endpoint simples para testes de disponibilidade.
     * O monitor poderá utilizá-lo sem alterar dados.
     */
    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("pong");
    }
}
EOF

cat > "$APP/src/main/resources/application.properties" <<'EOF'
spring.application.name=stormops-demo
server.port=8080

# Expor apenas os endpoints necessários para observabilidade.
management.endpoints.web.exposure.include=health,info,prometheus
management.endpoint.health.probes.enabled=true

# Identificar a aplicação nas métricas.
management.metrics.tags.application=stormops-demo

# Permitir observar pedidos HTTP e duração das respostas.
management.metrics.distribution.percentiles-histogram.http.server.requests=true
EOF

echo ""
echo "[3/7] Criar teste automatizado"

cat > "$APP/src/test/java/com/stormops/demo/DemoControllerTest.java" <<'EOF'
package com.stormops.demo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste unitário sem dependência de Kubernetes ou Docker.
 * Verifica o comportamento básico antes do deployment.
 */
class DemoControllerTest {

    @Test
    void pingShouldReturnPong() {
        DemoController controller = new DemoController();

        assertEquals(200, controller.ping().getStatusCode().value());
        assertEquals("pong", controller.ping().getBody());
    }

    @Test
    void helloShouldContainServiceName() {
        DemoController controller = new DemoController();

        assertEquals("stormops-demo", controller.hello().get("service"));
    }
}
EOF

echo ""
echo "[4/7] Compilar e executar testes"

cd "$APP"
chmod +x mvnw

./mvnw -B clean verify

echo ""
echo "[5/7] Criar imagem Docker"

cat > "$APP/Dockerfile" <<'EOF'
# A aplicação é compilada previamente pelo Maven Wrapper.
# A imagem contém apenas o JAR e o runtime Java.
# Não precisamos de instalar Maven dentro do container.

FROM eclipse-temurin:21-jre

WORKDIR /app

# Executar como utilizador não privilegiado.
RUN groupadd --system stormops \
    && useradd --system --gid stormops stormops

COPY target/*.jar /app/app.jar

USER stormops

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
EOF

docker build \
  --tag stormops/sample-java-service:local \
  "$APP"

echo ""
echo "[6/7] Importar imagem para Kubernetes"

"$KIND" load docker-image \
  stormops/sample-java-service:local \
  --name stormops-local

echo ""
echo "[7/7] Criar Deployment e Service"

cat > "$MANIFEST" <<'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: stormops-demo
  namespace: stormops-lab
  labels:
    app: stormops-demo
spec:
  replicas: 2
  selector:
    matchLabels:
      app: stormops-demo
  template:
    metadata:
      labels:
        app: stormops-demo
    spec:
      containers:
        - name: demo
          image: stormops/sample-java-service:local
          imagePullPolicy: Never
          ports:
            - name: http
              containerPort: 8080
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            initialDelaySeconds: 5
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            initialDelaySeconds: 15
            periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: stormops-demo
  namespace: stormops-lab
  labels:
    app: stormops-demo
spec:
  selector:
    app: stormops-demo
  ports:
    - name: http
      port: 8080
      targetPort: http
  type: ClusterIP
EOF

kubectl --context "$CONTEXT" apply -f "$MANIFEST"

kubectl --context "$CONTEXT" \
  rollout status deployment/stormops-demo \
  -n stormops-lab --timeout=180s

echo ""
echo "===== VALIDAR RESULTADO ====="

kubectl --context "$CONTEXT" \
  get pods,services,deployments -n stormops-lab

echo ""
echo "===== STORMOPS DEMO READY ====="
echo "Aplicação Java compilada e instalada no Kubernetes."
echo "Próxima etapa: Prometheus, Grafana e experiências."
