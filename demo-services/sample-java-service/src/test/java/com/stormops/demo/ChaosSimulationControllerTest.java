package com.stormops.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Tests the bounded fault contract without requiring Kubernetes or Docker. */
class ChaosSimulationControllerTest {

    private final ChaosSimulationController controller = new ChaosSimulationController();

    @Test
    void baselineRemainsHealthy() {
        assertEquals("OK", controller.baseline().get("status"));
    }

    @Test
    void latencyIsBounded() throws InterruptedException {
        assertEquals(HttpStatus.OK, controller.latency(0).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.latency(-1).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.latency(2001).getStatusCode());
    }

    @Test
    void injectedErrorReturnsServiceUnavailable() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.error().getStatusCode());
    }
}
