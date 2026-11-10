#!/bin/bash
set -Eeuo pipefail

PROJECT="$HOME/Projects/StormOps"
BACKEND="$PROJECT/backend"
DEMO="$PROJECT/demo-services/sample-java-service"
REPORT="$PROJECT/stormops-phase7-inspection.txt"

if [[ ! -d "$BACKEND" || ! -d "$DEMO" ]]; then
    echo "Required StormOps directories are missing." >&2
    exit 1
fi

{
    echo "===== STORMOPS PHASE 7 INSPECTION ====="
    echo "Generated: $(date)"
    echo

    echo "===== BACKEND JAVA FILES ====="
    find "$BACKEND/src/main/java" \
        -type f -name '*.java' | sort
    echo

    echo "===== BACKEND JAVA SOURCE ====="
    while IFS= read -r file; do
        echo
        echo "===== FILE: ${file#"$PROJECT/"} ====="
        cat "$file"
    done < <(
        find "$BACKEND/src/main/java" \
            -type f -name '*.java' | sort
    )

    echo
    echo "===== BACKEND MIGRATIONS ====="
    while IFS= read -r file; do
        echo
        echo "===== FILE: ${file#"$PROJECT/"} ====="
        cat "$file"
    done < <(
        find "$BACKEND/src/main/resources/db/migration" \
            -type f -name '*.sql' | sort
    )

    echo
    echo "===== DEMO JAVA SOURCE ====="
    while IFS= read -r file; do
        echo
        echo "===== FILE: ${file#"$PROJECT/"} ====="
        cat "$file"
    done < <(
        find "$DEMO/src/main/java" \
            -type f -name '*.java' | sort
    )

    echo
    echo "===== DEMO APPLICATION CONFIGURATION ====="
    if [[ -f "$DEMO/src/main/resources/application.properties" ]]; then
        sed -E \
            '/password|secret|token|credential|api.key|private.key/Id' \
            "$DEMO/src/main/resources/application.properties"
    fi

    echo
    echo "===== KUBERNETES DEMO MANIFEST ====="
    if [[ -f "$PROJECT/infrastructure/kubernetes/demo-service.yaml" ]]; then
        cat "$PROJECT/infrastructure/kubernetes/demo-service.yaml"
    fi

    echo
    echo "===== BACKEND DEPENDENCY ARTIFACTS ====="
    grep -E '<artifactId>' "$BACKEND/pom.xml"

    echo
    echo "===== DEMO DEPENDENCY ARTIFACTS ====="
    grep -E '<artifactId>' "$DEMO/pom.xml"

    echo
    echo "===== CURRENT API HEALTH ====="
    curl --silent --show-error --max-time 5 \
        "http://127.0.0.1:18080/actuator/health" || true

    echo
    echo
    echo "===== CURRENT KUBERNETES DEMO PODS ====="
    KUBECONFIG="$PROJECT/.kube/stormops-config" \
        kubectl --context kind-stormops-local \
        -n stormops-lab \
        get pods -l app=stormops-demo \
        --no-headers 2>&1 || true

} > "$REPORT"

echo "Inspection completed."
echo "Report: $REPORT"
echo "Size: $(wc -c < "$REPORT" | tr -d ' ') bytes"
echo
echo "No source files or infrastructure resources were modified."
