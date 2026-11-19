package com.stormops.api.api;

import com.stormops.api.core.PrometheusGuard;
import com.stormops.api.persistence.SafetyRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only operational evidence, not a readiness authorization or fault trigger.
 * Unknown database state always fails closed without querying external workloads.
 */
@RestController
@RequestMapping("/api/v1/observability")
public class ObservabilityController {
    private final SafetyRepository safety;
    private final PrometheusGuard telemetry;

    public ObservabilityController(SafetyRepository safety, PrometheusGuard telemetry) {
        this.safety = safety;
        this.telemetry = telemetry;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> persisted;
        try {
            persisted = safety.state();
        } catch (DataAccessException unavailable) {
            // No operational state can be inferred when PostgreSQL is unavailable.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    Map.of(
                            "operationalState", "UNKNOWN",
                            "safetyState", "UNAVAILABLE",
                            "telemetryState", "NOT_CHECKED",
                            "interpretation", "Database safety evidence unavailable; no execution is authorized."));
        }

        Object stoppedValue = persisted.get("stopped");
        Object activeRun = persisted.get("active_run");
        if (!(stoppedValue instanceof Boolean stopped)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    Map.of(
                            "operationalState", "UNKNOWN",
                            "safetyState", "INVALID",
                            "telemetryState", "NOT_CHECKED",
                            "interpretation", "Invalid persistent safety state; no execution is authorized."));
        }

        String telemetryState;
        try {
            telemetry.verifyHealthy();
            telemetryState = "HEALTHY";
        } catch (IllegalStateException unsafeEvidence) {
            // The guard deliberately does not reveal internal Prometheus responses.
            telemetryState = "UNAVAILABLE_OR_UNSAFE";
        }

        String safetyState = activeRun != null ? "REVIEW_REQUIRED"
                : stopped ? "STOPPED" : "UNRESERVED";
        String operationalState = activeRun != null ? "REVIEW_REQUIRED"
                : stopped ? "STOPPED"
                : "HEALTHY".equals(telemetryState) ? "PRECHECK_REQUIRED" : "TELEMETRY_NOT_READY";

        var result = new LinkedHashMap<String, Object>();
        result.put("operationalState", operationalState);
        result.put("safetyState", safetyState);
        result.put("stopped", stopped);
        result.put("activeRun", activeRun == null ? null : activeRun.toString());
        result.put("telemetryState", telemetryState);
        result.put("interpretation",
                "Read-only evidence snapshot; Kubernetes readiness, baseline traffic, "
                + "run authorization and all execution checkpoints must still be verified.");
        return ResponseEntity.ok(result);
    }
}
