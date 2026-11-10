package com.stormops.api.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ReliabilityControllerTest {
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ReliabilityController controllerWith(List<Map<String, Object>> rows) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), (RowMapper) any(RowMapper.class))).thenReturn((List) rows);
        return new ReliabilityController(jdbc);
    }

    private Map<String, Object> record(String type, String status, long duration) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("status", status);
        item.put("durationMillis", duration);
        item.put("id", "test-id");
        return item;
    }

    @Test void evaluatesHistoricalScenariosWithoutInjectingFaults() {
        var controller = controllerWith(List.of(
            record("POD_TERMINATION", "RECOVERED", 19668L),
            record("HTTP_LATENCY", "RECOVERED", 514L),
            record("HTTP_ERROR", "RECOVERED", 4L),
            record("OTHER", "RECOVERED", 10L)));
        var report = controller.report(15000, 400);
        assertEquals(4, report.get("total"));
        assertEquals(1L, report.get("passed"));
        assertEquals(2L, report.get("failed"));
        assertEquals(1L, report.get("unsupported"));
        assertEquals(33.33, (Double) report.get("passRatePercent"), 0.001);
        var evaluations = (List<?>) report.get("evaluations");
        assertEquals("FAILED", ((Map<?, ?>) evaluations.get(0)).get("verdict"));
        assertEquals("FAILED", ((Map<?, ?>) evaluations.get(1)).get("verdict"));
        assertEquals("PASSED", ((Map<?, ?>) evaluations.get(2)).get("verdict"));
        assertEquals("UNSUPPORTED", ((Map<?, ?>) evaluations.get(3)).get("verdict"));
    }

    @Test void emptyHistoryHasNoMisleadingPassRate() {
        var report = controllerWith(List.of()).report(20000, 800);
        assertEquals(0, report.get("total"));
        assertNull(report.get("passRatePercent"));
    }

    @Test void rejectsInvalidThresholds() {
        var controller = controllerWith(List.of());
        assertThrows(IllegalArgumentException.class, () -> controller.report(0, 800));
        assertThrows(IllegalArgumentException.class, () -> controller.report(20000, 600001));
    }
}
