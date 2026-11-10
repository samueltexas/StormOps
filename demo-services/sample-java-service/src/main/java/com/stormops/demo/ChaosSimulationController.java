package com.stormops.demo;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provides bounded, request-scoped failure simulations for the isolated lab.
 * No persistent fault state is created and health probes remain unaffected.
 * These endpoints are not a substitute for authenticated experiment control.
 */
@RestController
@RequestMapping("/api/simulations")
public class ChaosSimulationController {

    private static final int MAX_LATENCY_MILLIS = 2000;

    @GetMapping("/baseline")
    public Map<String, Object> baseline() {
        return Map.of("scenario", "BASELINE", "status", "OK");
    }

    @GetMapping("/latency")
    public ResponseEntity<Map<String, Object>> latency(
            @RequestParam(defaultValue = "500") int milliseconds
    ) throws InterruptedException {
        if (milliseconds < 0 || milliseconds > MAX_LATENCY_MILLIS) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "milliseconds must be between 0 and 2000"
            ));
        }

        // Request-scoped delay prevents changes to shared application state.
        TimeUnit.MILLISECONDS.sleep(milliseconds);
        return ResponseEntity.ok(Map.of(
                "scenario", "HTTP_LATENCY",
                "injectedLatencyMillis", milliseconds,
                "status", "OK"
        ));
    }

    @GetMapping("/error")
    public ResponseEntity<Map<String, Object>> error() {
        // The fault affects only this endpoint, never liveness/readiness probes.
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "scenario", "HTTP_ERROR",
                "status", "INJECTED_FAILURE",
                "httpStatus", 503
        ));
    }
}
