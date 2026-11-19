package com.stormops.api.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.stormops.api.kubernetes.KubernetesGateway;
import com.stormops.api.persistence.ExperimentRepository;
import com.stormops.api.persistence.SafetyRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExperimentSafetyTest {
    KubernetesGateway kubernetes = mock(KubernetesGateway.class);
    ExperimentRepository repository = mock(ExperimentRepository.class);
    HttpSimulationGateway http = mock(HttpSimulationGateway.class);
    SafetyRepository safety = mock(SafetyRepository.class);
    PrometheusGuard telemetry = mock(PrometheusGuard.class);
    ExperimentService service = new ExperimentService(kubernetes, repository, http, safety, telemetry);

    @BeforeEach void healthy() {
        when(kubernetes.desiredReplicas()).thenReturn(2);
        when(kubernetes.readyReplicas()).thenReturn(2);
        when(kubernetes.runningPods()).thenReturn(List.of("stormops-demo-a", "stormops-demo-b"));
        when(http.invoke("BASELINE", 0)).thenReturn(new HttpSimulationGateway.Observation(200, 5, "ok"));
        doAnswer(call -> { ((Runnable)call.getArgument(4)).run(); return null; }).when(safety).finish(any(), anyString(), anyString(), anyBoolean(), any());
    }
    @Test void healthyPodRecoveryRequiresPostcheck() {
        service = new ExperimentService(kubernetes, repository, http, safety, telemetry, java.time.Duration.ofSeconds(5), java.time.Duration.ZERO);
        when(kubernetes.podExists("stormops-demo-a")).thenReturn(false);
        assertEquals("RECOVERED", service.terminateOnePod(false).status());
        verify(safety).audit(any(), anyString(), eq("POSTCHECK_PASSED"), anyString());
    }
    @Test void expectedHttpErrorStillRequiresHealthyPostcheck() {
        service = new ExperimentService(kubernetes, repository, http, safety, telemetry, java.time.Duration.ofSeconds(5), java.time.Duration.ZERO);
        when(http.invoke("HTTP_ERROR", 0)).thenReturn(new HttpSimulationGateway.Observation(503, 12, "injected"));
        assertEquals("RECOVERED", service.runHttpSimulation("HTTP_ERROR", 0).status());
        verify(http, times(2)).invoke("BASELINE", 0);
    }
    @Test void deadlinePreventsInjection() {
        service = new ExperimentService(kubernetes, repository, http, safety, telemetry, java.time.Duration.ZERO, java.time.Duration.ZERO);
        assertEquals("TIMED_OUT", service.terminateOnePod(false).status());
        verifyNoInteractions(kubernetes, http, telemetry);
    }
    @Test void dryRunChecksHealthWithoutInjection() {
        var result = service.terminateOnePod(true);
        assertEquals("DRY_RUN", result.status());
        verify(kubernetes, never()).deletePod(anyString());
        verify(telemetry).verifyHealthy();
        verify(repository).save(result);
    }
    @Test void unavailableAuditStorePreventsAllExternalCalls() {
        doThrow(new IllegalStateException()).when(safety).begin(any(), anyString(), anyString(), anyBoolean());
        assertThrows(IllegalStateException.class, () -> service.terminateOnePod(false));
        verifyNoInteractions(kubernetes, http, telemetry);
    }
    @Test void missingTelemetryPreventsInjection() {
        doThrow(new IllegalStateException()).when(telemetry).verifyHealthy();
        assertEquals("FAILED", service.terminateOnePod(false).status());
        verify(kubernetes, never()).deletePod(anyString());
    }
    @Test void injectionIntentMustPersistBeforeDelete() {
        doThrow(new IllegalStateException()).when(safety).audit(any(), anyString(), eq("INJECTION_INTENT"), anyString());
        assertEquals("FAILED", service.terminateOnePod(false).status());
        verify(kubernetes, never()).deletePod(anyString());
    }
    @Test void abortAfterInjectionLatchesStop() {
        doNothing().doThrow(new IllegalStateException()).when(telemetry).verifyHealthy();
        var result = service.terminateOnePod(false);
        assertEquals("ABORTED", result.status());
        verify(kubernetes, times(1)).deletePod("stormops-demo-a");
        verify(safety).finish(eq(result.id()), anyString(), eq("ABORTED"), eq(true), any());
    }
    @Test void killSwitchBeforeInjectionPreventsMutation() {
        doNothing().doThrow(new IllegalStateException()).when(safety).checkpoint(any(), anyBoolean());
        assertEquals("FAILED", service.terminateOnePod(false).status());
        verify(kubernetes, never()).deletePod(anyString());
    }
    @Test void outcomeWriteFailureIsVisible() {
        doThrow(new IllegalStateException()).when(safety).finish(any(), anyString(), anyString(), anyBoolean(), any());
        assertThrows(IllegalStateException.class, () -> service.terminateOnePod(true));
    }
    @Test void invalidHttpInputNeverAcquiresReservation() {
        assertThrows(IllegalArgumentException.class, () -> service.runHttpSimulation("HTTP_LATENCY", 2001));
        assertThrows(IllegalArgumentException.class, () -> service.runHttpSimulation("HTTP_ERROR", 1));
        verifyNoInteractions(safety);
    }
    @Test void httpDryRunDoesNotInjectError() {
        assertEquals("DRY_RUN", service.runHttpSimulation("HTTP_ERROR", 0, true).status());
        verify(http, never()).invoke("HTTP_ERROR", 0);
    }
}
