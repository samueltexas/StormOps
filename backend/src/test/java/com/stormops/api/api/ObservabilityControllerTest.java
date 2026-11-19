package com.stormops.api.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.stormops.api.core.PrometheusGuard;
import com.stormops.api.persistence.SafetyRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

class ObservabilityControllerTest {
    private final SafetyRepository safety = mock(SafetyRepository.class);
    private final PrometheusGuard telemetry = mock(PrometheusGuard.class);
    private final ObservabilityController controller =
            new ObservabilityController(safety, telemetry);

    @Test
    void unresolvedRunOverridesHealthyTelemetryAndDoesNotMutateSafety() {
        UUID runId = UUID.randomUUID();
        when(safety.state()).thenReturn(Map.of("stopped", false, "active_run", runId));
        var response = controller.status();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("REVIEW_REQUIRED", response.getBody().get("operationalState"));
        assertEquals(runId.toString(), response.getBody().get("activeRun"));
        assertEquals("HEALTHY", response.getBody().get("telemetryState"));
        verify(safety, never()).stop(anyBoolean(), anyString());
        verify(safety, never()).begin(any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void stoppedStateNeverClaimsPrecheckReadiness() {
        when(safety.state()).thenReturn(Map.of("stopped", true));
        var response = controller.status();
        assertEquals("STOPPED", response.getBody().get("operationalState"));
        assertEquals("STOPPED", response.getBody().get("safetyState"));
        assertNull(response.getBody().get("activeRun"));
    }

    @Test
    void missingTelemetryFailsClosedWithoutInventingHealthyStatus() {
        when(safety.state()).thenReturn(Map.of("stopped", false));
        doThrow(new IllegalStateException("not reachable")).when(telemetry).verifyHealthy();
        var response = controller.status();
        assertEquals("TELEMETRY_NOT_READY", response.getBody().get("operationalState"));
        assertEquals("UNAVAILABLE_OR_UNSAFE", response.getBody().get("telemetryState"));
        assertFalse(response.getBody().toString().contains("not reachable"));
    }

    @Test
    void availableTelemetryStillRequiresFullExecutionPrechecks() {
        when(safety.state()).thenReturn(Map.of("stopped", false));
        var response = controller.status();
        assertEquals("PRECHECK_REQUIRED", response.getBody().get("operationalState"));
        assertEquals("HEALTHY", response.getBody().get("telemetryState"));
        assertTrue(response.getBody().get("interpretation").toString()
                .contains("execution checkpoints"));
    }

    @Test
    void databaseOutageReturnsServiceUnavailableWithoutProbingTelemetry() {
        when(safety.state()).thenThrow(new DataAccessResourceFailureException("secret JDBC URL"));
        var response = controller.status();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("UNKNOWN", response.getBody().get("operationalState"));
        assertEquals("NOT_CHECKED", response.getBody().get("telemetryState"));
        assertFalse(response.getBody().toString().contains("secret JDBC URL"));
        verifyNoInteractions(telemetry);
    }

    @Test
    void malformedDatabaseStateIsUnavailableAndDoesNotProbeTelemetry() {
        when(safety.state()).thenReturn(Map.of("stopped", "false"));
        var response = controller.status();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("INVALID", response.getBody().get("safetyState"));
        verifyNoInteractions(telemetry);
    }
}
