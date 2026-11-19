package com.stormops.api.api;

import com.stormops.api.core.ExperimentService;
import com.stormops.api.persistence.SafetyRepository;
import java.util.Map;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/safety")
public class SafetyController {
    private final SafetyRepository safety;
    public SafetyController(SafetyRepository safety) { this.safety = safety; }
    @GetMapping public Map<String,Object> state() { return safety.state(); }
    @GetMapping("/audit") public List<Map<String,Object>> audit() { return safety.auditTrail(); }
    @PostMapping("/stop") public Map<String,Object> stop(@RequestHeader("X-StormOps-Confirmation") String confirmation) {
        return change(true, confirmation);
    }
    @PostMapping("/resume") public Map<String,Object> resume(@RequestHeader("X-StormOps-Confirmation") String confirmation) {
        return change(false, confirmation);
    }
    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> handleStatus(
            ResponseStatusException exception) {
        // Resolve the expected error inside MVC to preserve the precise status
        // without an /error dispatch that could be denied by the security filter.
        return org.springframework.http.ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("error", exception.getReason() == null
                        ? "Safety operation rejected" : exception.getReason()));
    }

    private Map<String,Object> change(boolean stopped, String confirmation) {
        if (!(stopped ? "STOP_STORMOPS" : "RESUME_STORMOPS").equals(confirmation)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Explicit confirmation is required");
        try { safety.stop(stopped, ExperimentService.actor()); }
        catch (IllegalStateException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Experiment requires completion or offline recovery"); }
        return safety.state();
    }
}
