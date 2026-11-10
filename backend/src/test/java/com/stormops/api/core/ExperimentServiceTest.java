package com.stormops.api.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stormops.api.kubernetes.KubernetesGateway;
import com.stormops.api.persistence.ExperimentRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExperimentServiceTest {

    @Test
    void rejectsDegradedDeploymentAndPersistsFailure() {
        KubernetesGateway gateway = mock(KubernetesGateway.class);
        ExperimentRepository repository = mock(ExperimentRepository.class);
        when(gateway.desiredReplicas()).thenReturn(2);
        when(gateway.readyReplicas()).thenReturn(1);

        ExperimentResult result = new ExperimentService(gateway, repository).terminateOnePod();

        assertEquals("FAILED", result.status());
        verify(repository).save(result);
    }

    @Test
    void validatesPaginationBeforeQueryingDatabase() {
        ExperimentService service = new ExperimentService(
                mock(KubernetesGateway.class), mock(ExperimentRepository.class)
        );
        assertThrows(IllegalArgumentException.class, () -> service.history(-1, 20));
        assertThrows(IllegalArgumentException.class, () -> service.history(0, 101));
    }

    @Test
    void returnsPersistentHistory() {
        ExperimentRepository repository = mock(ExperimentRepository.class);
        when(repository.findRecent(100, 0)).thenReturn(List.of());
        ExperimentService service = new ExperimentService(mock(KubernetesGateway.class), repository);
        assertEquals(List.of(), service.history());
        verify(repository).findRecent(100, 0);
    }
}
