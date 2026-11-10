package com.stormops.api.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stormops.api.kubernetes.KubernetesGateway;
import com.stormops.api.persistence.ExperimentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ExperimentServiceTestPhase7B {

    @Mock private KubernetesGateway kubernetes;
    @Mock private ExperimentRepository repository;
    @Mock private HttpSimulationGateway http;

    private ExperimentService service;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        service = new ExperimentService(kubernetes, repository, http);
    }

    @Test
    void expected503IsSuccessfulFaultInjection() {
        when(http.invoke("HTTP_ERROR", 0))
                .thenReturn(new HttpSimulationGateway.Observation(503, 12, "injected"));

        ExperimentResult result = service.runHttpSimulation("HTTP_ERROR", 0);

        assertEquals("RECOVERED", result.status());
        assertEquals("HTTP_ERROR", result.type());
        verify(repository).save(any(ExperimentResult.class));
    }

    @Test
    void latencyAboveLimitIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.runHttpSimulation("HTTP_LATENCY", 2001));
    }

    @Test
    void baseline200IsSuccessful() {
        when(http.invoke("BASELINE", 0))
                .thenReturn(new HttpSimulationGateway.Observation(200, 5, "ok"));

        assertEquals("RECOVERED", service.runHttpSimulation("BASELINE", 0).status());
    }
}
