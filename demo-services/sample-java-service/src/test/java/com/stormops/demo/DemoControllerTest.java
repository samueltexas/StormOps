package com.stormops.demo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste unitário sem dependência de Kubernetes ou Docker.
 * Verifica o comportamento básico antes do deployment.
 */
class DemoControllerTest {

    @Test
    void pingShouldReturnPong() {
        DemoController controller = new DemoController();

        assertEquals(200, controller.ping().getStatusCode().value());
        assertEquals("pong", controller.ping().getBody());
    }

    @Test
    void helloShouldContainServiceName() {
        DemoController controller = new DemoController();

        assertEquals("stormops-demo", controller.hello().get("service"));
    }
}
