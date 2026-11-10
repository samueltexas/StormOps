package com.stormops.api.api;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only evaluations of recorded experiments; not a live availability SLO. */
@RestController
@RequestMapping("/api/v1/reliability")
public class ReliabilityController {
    private final JdbcTemplate jdbc;

    public ReliabilityController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/report")
    public Map<String, Object> report(
            @RequestParam(defaultValue = "20000") long podRecoveryThresholdMillis,
            @RequestParam(defaultValue = "800") long httpResponseThresholdMillis) {
        if (podRecoveryThresholdMillis < 1 || podRecoveryThresholdMillis > 600000
                || httpResponseThresholdMillis < 1 || httpResponseThresholdMillis > 600000) {
            throw new IllegalArgumentException("Thresholds must be between 1 and 600000 milliseconds");
        }
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT id, started_at, experiment_type, target, status, recovery_millis, detail
                FROM experiments ORDER BY started_at DESC, id DESC
                """, (rs, index) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getObject("id").toString());
            item.put("startedAt", rs.getTimestamp("started_at").toInstant().toString());
            item.put("type", rs.getString("experiment_type"));
            item.put("target", rs.getString("target"));
            item.put("status", rs.getString("status"));
            item.put("durationMillis", rs.getLong("recovery_millis"));
            item.put("detail", rs.getString("detail"));
            return item;
        });
        List<Map<String, Object>> evaluations = new ArrayList<>();
        long passed = 0;
        long failed = 0;
        long unsupported = 0;
        for (Map<String, Object> record : rows) {
            String type = (String) record.get("type");
            String status = (String) record.get("status");
            long duration = (Long) record.get("durationMillis");
            Long threshold = switch (type) {
                case "POD_TERMINATION" -> podRecoveryThresholdMillis;
                case "BASELINE", "HTTP_LATENCY", "HTTP_ERROR" -> httpResponseThresholdMillis;
                default -> null;
            };
            String verdict;
            if (threshold == null) {
                verdict = "UNSUPPORTED";
                unsupported++;
            } else if ("RECOVERED".equals(status) && duration <= threshold) {
                verdict = "PASSED";
                passed++;
            } else {
                verdict = "FAILED";
                failed++;
            }
            Map<String, Object> evaluation = new LinkedHashMap<>(record);
            evaluation.put("thresholdMillis", threshold);
            evaluation.put("verdict", verdict);
            evaluations.add(evaluation);
        }
        Map<String, Object> policy = Map.of(
                "podRecoveryThresholdMillis", podRecoveryThresholdMillis,
                "httpResponseThresholdMillis", httpResponseThresholdMillis);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("policy", policy);
        report.put("total", rows.size());
        report.put("passed", passed);
        report.put("failed", failed);
        report.put("unsupported", unsupported);
        report.put("passRatePercent", passed + failed == 0 ? null :
                Math.round(10000.0 * passed / (passed + failed)) / 100.0);
        report.put("interpretation", "Historical experiment assertions only; not service uptime or an availability SLO.");
        report.put("evaluations", evaluations);
        return report;
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> invalid(IllegalArgumentException ex) {
        return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
