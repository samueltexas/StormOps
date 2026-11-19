package com.stormops.api.kubernetes;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class KubernetesGatewayTest {
    private static final String PODS = """
        {"items":[{"metadata":{"name":"stormops-demo-abc-123","namespace":"stormops-lab",
        "labels":{"app":"stormops-demo"},"ownerReferences":[{"controller":true,"kind":"ReplicaSet","name":"stormops-demo-abc"}]},
        "status":{"phase":"Running","conditions":[{"type":"Ready","status":"True"}]}}]}
        """;
    @Test void admitsOnlyHealthyAllowlistedManagedPods() {
        assertEquals(List.of("stormops-demo-abc-123"), KubernetesGateway.eligiblePods(PODS));
        assertTrue(KubernetesGateway.eligiblePods(PODS.replace("stormops-lab", "production")).isEmpty());
        assertTrue(KubernetesGateway.eligiblePods(PODS.replace("\"app\":\"stormops-demo\"", "\"app\":\"other\"")).isEmpty());
        assertTrue(KubernetesGateway.eligiblePods(PODS.replace("\"True\"", "\"False\"")).isEmpty());
        assertTrue(KubernetesGateway.eligiblePods(PODS.replace("ReplicaSet", "Job")).isEmpty());
        assertTrue(KubernetesGateway.eligiblePods(PODS.replace("\"labels\":", "\"deletionTimestamp\":\"2026-01-01T00:00:00Z\",\"labels\":")).isEmpty());
    }
    @Test void rejectsUntrustedNamesBeforeInvokingKubectl() {
        var gateway = new KubernetesGateway();
        assertThrows(IllegalArgumentException.class, () -> gateway.deletePod("--all"));
        assertThrows(IllegalArgumentException.class, () -> gateway.deletePod("other-service-abc"));
    }
}
