package com.stormops.api.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.stormops.api.kubernetes.KubernetesGateway;
import com.stormops.api.persistence.ExperimentRepository;
import com.stormops.api.persistence.SafetyRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Operational failure tests run entirely against mocks; no pod is deleted.
 * Database crash persistence is tested separately in Testcontainers.
 */
class OperationalFailureTest {
    private final KubernetesGateway kubernetes = mock(KubernetesGateway.class);
    private final ExperimentRepository repository = mock(ExperimentRepository.class);
    private final HttpSimulationGateway http = mock(HttpSimulationGateway.class);
    private final SafetyRepository safety = mock(SafetyRepository.class);
    private final PrometheusGuard telemetry = mock(PrometheusGuard.class);

    private ExperimentService service() {
        return new ExperimentService(
                kubernetes, repository, http, safety, telemetry,
                Duration.ofSeconds(10), Duration.ZERO);
    }

    @BeforeEach
    void healthyWorkload() {
        when(kubernetes.desiredReplicas()).thenReturn(2);
        when(kubernetes.readyReplicas()).thenReturn(2);
        when(kubernetes.runningPods()).thenReturn(
                List.of("stormops-demo-a", "stormops-demo-b"));
        when(http.invoke("BASELINE", 0)).thenReturn(
                new HttpSimulationGateway.Observation(200, 5, "ok"));
    }

    @Test
    void databaseAdmissionFailurePreventsAnyExternalOperation() {
        doThrow(new IllegalStateException("Database is unavailable"))
                .when(safety).begin(any(), anyString(), anyString(), anyBoolean());

        assertThrows(IllegalStateException.class,
                () -> service().terminateOnePod(false));

        verifyNoInteractions(kubernetes, http, telemetry);
        verify(kubernetes, never()).deletePod(anyString());
        verify(safety, never()).audit(any(), anyString(), eq("INJECTION_INTENT"), anyString());
    }

    @Test
    void missingTelemetryBeforeInjectionCannotDeletePod() {
        doThrow(new IllegalStateException("Prometheus unavailable"))
                .when(telemetry).verifyHealthy();

        ExperimentResult result = service().terminateOnePod(false);

        assertEquals("FAILED", result.status());
        verify(kubernetes, never()).deletePod(anyString());
        verify(safety, never()).audit(any(), anyString(), eq("INJECTION_INTENT"), anyString());
        verify(safety).finish(eq(result.id()), anyString(), eq("FAILED"), eq(false), any());
    }

    @Test
    void unavailableKubernetesCredentialsBeforeIntentPreventInjection() {
        when(kubernetes.desiredReplicas())
                .thenThrow(new IllegalStateException("Runner token expired"));

        ExperimentResult result = service().terminateOnePod(false);

        assertEquals("FAILED", result.status());
        verify(kubernetes, never()).deletePod(anyString());
        verify(safety, never()).audit(any(), anyString(), eq("INJECTION_INTENT"), anyString());
    }

    @Test
    void lossOfRunnerCredentialsDuringDeletionLatchesStopWithoutRetry() {
        doThrow(new IllegalStateException("Runner access revoked"))
                .when(kubernetes).deletePod("stormops-demo-a");

        ExperimentResult result = service().terminateOnePod(false);

        assertEquals("ABORTED", result.status());
        InOrder order = inOrder(safety, kubernetes);
        order.verify(safety).audit(eq(result.id()), anyString(),
                eq("INJECTION_INTENT"), anyString());
        order.verify(kubernetes).deletePod("stormops-demo-a");
        verify(kubernetes, times(1)).deletePod(anyString());
        verify(safety).finish(eq(result.id()), anyString(), eq("ABORTED"), eq(true), any());
    }

    @Test
    void completionFailureAfterPodTerminationNeverRetriesFault() {
        when(kubernetes.podExists("stormops-demo-a")).thenReturn(false);
        doThrow(new IllegalStateException("Database commit unavailable"))
                .when(safety).finish(any(), anyString(), anyString(), anyBoolean(), any());

        assertThrows(IllegalStateException.class,
                () -> service().terminateOnePod(false));

        verify(kubernetes, times(1)).deletePod("stormops-demo-a");
        verify(safety).audit(any(), anyString(), eq("INJECTION_INTENT"), anyString());
        verify(safety).finish(any(), anyString(), eq("RECOVERED"), eq(false), any());
    }
}
