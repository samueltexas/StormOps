package com.stormops.demo;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serviço de demonstração do StormOps.
 *
 * As respostas HTTP permitem medir disponibilidade e latência.
 * O contador utiliza AtomicLong para suportar pedidos simultâneos
 * sem perder incrementos entre diferentes threads.
 *
 * Cada réplica mantém o seu contador em memória. Por isso,
 * este valor não representa o total global do Kubernetes.
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    private final AtomicLong requestCounter = new AtomicLong();

    @GetMapping("/hello")
    public Map<String, Object> hello() {
        long count = requestCounter.incrementAndGet();

        return Map.of(
            "service", "stormops-demo",
            "message", "StormOps Java service is running",
            "requestCount", count,
            "timestamp", Instant.now().toString()
        );
    }

    /**
     * Endpoint simples para testes de disponibilidade.
     * O monitor poderá utilizá-lo sem alterar dados.
     */
    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("pong");
    }
}
