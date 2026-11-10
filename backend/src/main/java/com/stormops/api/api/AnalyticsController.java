package com.stormops.api.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only analytics. Recovery applies to pod termination; HTTP duration is response time. */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {
    private final JdbcTemplate jdbc;

    public AnalyticsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", jdbc.queryForObject("SELECT COUNT(*) FROM experiments", Long.class));
        result.put("passed", jdbc.queryForObject("SELECT COUNT(*) FROM experiments WHERE status = 'RECOVERED'", Long.class));
        result.put("failed", jdbc.queryForObject("SELECT COUNT(*) FROM experiments WHERE status = 'FAILED'", Long.class));
        result.put("podRecoveryAvgMillis", jdbc.queryForObject("""
                SELECT ROUND(AVG(recovery_millis))::bigint FROM experiments
                WHERE experiment_type = 'POD_TERMINATION' AND status = 'RECOVERED'
                """, Long.class));
        result.put("httpResponseAvgMillis", jdbc.queryForObject("""
                SELECT ROUND(AVG(recovery_millis))::bigint FROM experiments
                WHERE experiment_type IN ('BASELINE', 'HTTP_LATENCY', 'HTTP_ERROR')
                  AND status = 'RECOVERED'
                """, Long.class));
        result.put("byType", jdbc.query("""
                SELECT experiment_type, COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE status = 'RECOVERED') AS passed,
                       COUNT(*) FILTER (WHERE status = 'FAILED') AS failed,
                       ROUND(AVG(recovery_millis))::bigint AS avg_millis
                FROM experiments GROUP BY experiment_type ORDER BY experiment_type
                """, (rs, i) -> Map.of(
                    "type", rs.getString("experiment_type"),
                    "total", rs.getLong("total"),
                    "passed", rs.getLong("passed"),
                    "failed", rs.getLong("failed"),
                    "avgMillis", rs.getLong("avg_millis")
                )));
        result.put("recent", jdbc.query("""
                SELECT id, started_at, experiment_type, status, recovery_millis
                FROM experiments ORDER BY started_at DESC, id DESC LIMIT 100
                """, (rs, i) -> Map.of(
                    "id", rs.getObject("id").toString(),
                    "startedAt", rs.getTimestamp("started_at").toInstant().toString(),
                    "type", rs.getString("experiment_type"),
                    "status", rs.getString("status"),
                    "durationMillis", rs.getLong("recovery_millis")
                )));
        return result;
    }
}
