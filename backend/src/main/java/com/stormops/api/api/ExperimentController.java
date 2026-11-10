package com.stormops.api.api;

import com.stormops.api.core.ExperimentResult;
import com.stormops.api.core.ExperimentService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/experiments")
public class ExperimentController {

    private static final String CONFIRMATION = "TERMINATE_STORMOPS_DEMO_POD";
    private static final String HTTP_CONFIRMATION = "RUN_STORMOPS_HTTP_SIMULATION";
    private final ExperimentService experiments;

    public ExperimentController(ExperimentService experiments) {
        this.experiments = experiments;
    }

    @GetMapping
    public List<ExperimentResult> list() {
        // Keep the original endpoint contract for existing clients.
        return experiments.history();
    }

    @GetMapping("/page")
    public Map<String, Object> page(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return Map.of(
                "items", experiments.history(page, size),
                "page", page,
                "size", size,
                "total", experiments.count()
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<ExperimentResult> find(@PathVariable UUID id) {
        ExperimentResult result = experiments.findById(id);
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    @PostMapping("/pod-termination")
    public ExperimentResult terminatePod(
            @RequestHeader("X-StormOps-Confirmation") String confirmation
    ) {
        // Confirmation mitigates accidental execution; it is not authentication.
        if (!CONFIRMATION.equals(confirmation)) {
            throw new IllegalArgumentException("Explicit confirmation is required");
        }
        return experiments.terminateOnePod();
    }


    @PostMapping("/http-simulation")
    public ExperimentResult simulateHttp(
            @RequestHeader("X-StormOps-Confirmation") String confirmation,
            @org.springframework.web.bind.annotation.RequestBody HttpSimulationRequest request
    ) {
        if (!HTTP_CONFIRMATION.equals(confirmation)) {
            throw new IllegalArgumentException("Explicit HTTP simulation confirmation is required");
        }
        if (request == null || request.type() == null) {
            throw new IllegalArgumentException("Experiment type is required");
        }
        return experiments.runHttpSimulation(
                request.type(), request.latencyMillis() == null ? 0 : request.latencyMillis());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(Exception exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", exception.getMessage()));
    }
}
