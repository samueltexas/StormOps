package com.stormops.api.core;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
class PrometheusGuardTest {
    Map<String,Object> sample(String value, long age) {
        return Map.of("status", "success", "data", Map.of("resultType", "vector", "result", List.of(Map.of("value", List.of(System.currentTimeMillis()/1000 - age, value)))));
    }
    @Test void acceptsFreshFiniteSample() { assertEquals(0.01, PrometheusGuard.value(sample("0.01", 0))); }
    @Test void rejectsStaleAndNonFiniteEvidence() {
        assertThrows(IllegalStateException.class, () -> PrometheusGuard.value(sample("0", 60)));
        assertThrows(IllegalStateException.class, () -> PrometheusGuard.value(sample("NaN", 0)));
        assertThrows(IllegalStateException.class, () -> PrometheusGuard.value(sample("+Inf", 0)));
        assertThrows(IllegalStateException.class, () -> PrometheusGuard.value(Map.of("status", "error")));
    }
}
