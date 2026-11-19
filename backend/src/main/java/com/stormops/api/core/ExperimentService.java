package com.stormops.api.core;

import com.stormops.api.kubernetes.KubernetesGateway;
import com.stormops.api.persistence.ExperimentRepository;
import com.stormops.api.persistence.SafetyRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class ExperimentService {
    private final KubernetesGateway kubernetes;
    private final ExperimentRepository repository;
    private final HttpSimulationGateway httpSimulations;
    private final SafetyRepository safety;
    private final PrometheusGuard telemetry;

    private final Duration budget;
    private final Duration observationWindow;

    @org.springframework.beans.factory.annotation.Autowired
    public ExperimentService(KubernetesGateway kubernetes, ExperimentRepository repository,
                             HttpSimulationGateway httpSimulations, SafetyRepository safety,
                             PrometheusGuard telemetry) {
        this(kubernetes, repository, httpSimulations, safety, telemetry, Duration.ofSeconds(90), Duration.ofSeconds(16));
    }
    ExperimentService(KubernetesGateway kubernetes, ExperimentRepository repository,
                      HttpSimulationGateway httpSimulations, SafetyRepository safety,
                      PrometheusGuard telemetry, Duration budget, Duration observationWindow) {
        this.budget = budget;
        this.observationWindow = observationWindow;
        this.kubernetes = kubernetes;
        this.repository = repository;
        this.httpSimulations = httpSimulations;
        this.safety = safety;
        this.telemetry = telemetry;
    }
    public List<ExperimentResult> history() {
        return repository.findRecent(100, 0);
    }

    public List<ExperimentResult> history(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid pagination parameters");
        }
        return repository.findRecent(size, page * size);
    }

    public long count() {
        return repository.count();
    }

    public ExperimentResult findById(UUID id) {
        return repository.findById(id);
    }


    public static String actor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "dev-anonymous" : auth.getName();
    }
    public ExperimentResult terminateOnePod() { return terminateOnePod(false); }
    public ExperimentResult terminateOnePod(boolean dryRun) { return execute("POD_TERMINATION", 0, dryRun); }
    public ExperimentResult runHttpSimulation(String type, int latencyMillis) {
        return runHttpSimulation(type, latencyMillis, false);
    }
    public ExperimentResult runHttpSimulation(String type, int latencyMillis, boolean dryRun) {
        if (!List.of("BASELINE", "HTTP_LATENCY", "HTTP_ERROR").contains(type)) throw new IllegalArgumentException("Unsupported HTTP experiment type");
        if (latencyMillis < 0 || latencyMillis > 2000 || (!"HTTP_LATENCY".equals(type) && latencyMillis != 0)) throw new IllegalArgumentException("Invalid latency (0..2000ms, HTTP_LATENCY only)");
        return execute(type, latencyMillis, dryRun);
    }
    private void healthy(Runnable check) {
        check.run();
        int desired = kubernetes.desiredReplicas();
        check.run();
        int ready = kubernetes.readyReplicas();
        check.run();
        int pods = kubernetes.runningPods().size();
        if (desired != 2 || ready != 2 || pods != 2) {
            throw new IllegalStateException("Exactly two healthy allowlisted replicas are required");
        }
        check.run();
        if (httpSimulations.invoke("BASELINE", 0).statusCode() != 200) throw new IllegalStateException("Baseline request failed");
        check.run();
        telemetry.verifyHealthy();
    }
    private void checkpoint(UUID id, boolean dryRun, long deadline) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Experiment interrupted");
        if (System.nanoTime() >= deadline) throw new DeadlineExceeded();
        safety.checkpoint(id, dryRun);
    }
    private ExperimentResult execute(String type, int latency, boolean dryRun) {
        UUID id = UUID.randomUUID();
        String actor = actor();
        Instant started = Instant.now();
        long deadline = System.nanoTime() + budget.toNanos();
        String target = "stormops-lab/app=stormops-demo";
        String status = "FAILED";
        String detail = "Safety precondition failed";
        long elapsed = -1;
        boolean attempted = false;
        // This durable reservation and STARTED audit must commit before any external action.
        safety.begin(id, actor, type, dryRun);
        try {
            checkpoint(id, dryRun, deadline);
            healthy(() -> checkpoint(id, dryRun, deadline));
            safety.audit(id, actor, "PRECHECK_PASSED", target);
            checkpoint(id, dryRun, deadline);
            if (dryRun) {
                status = "DRY_RUN";
                detail = "Preconditions passed; no fault injected; maximum pods=1, concurrency=1";
            } else {
                if ("POD_TERMINATION".equals(type)) target = kubernetes.runningPods().getFirst();
                safety.audit(id, actor, "INJECTION_INTENT", type + "; target=" + target);
                checkpoint(id, false, deadline);
                attempted = true;
                long injected = System.nanoTime();
                long responseMillis = -1;
                if ("POD_TERMINATION".equals(type)) {
                    kubernetes.deletePod(target);
                    while (true) {
                        checkpoint(id, false, deadline);
                        telemetry.verifyHealthy();
                        if (!kubernetes.podExists(target) && kubernetes.readyReplicas() == 2) break;
                        Thread.sleep(1000);
                    }
                } else {
                    var observed = httpSimulations.invoke(type, latency);
                    responseMillis = observed.elapsedMillis();
                    int expected = "HTTP_ERROR".equals(type) ? 503 : 200;
                    if (observed.statusCode() != expected) throw new IllegalStateException("Unexpected simulation response");
                }
                elapsed = responseMillis >= 0 ? responseMillis : Duration.ofNanos(System.nanoTime() - injected).toMillis();
                // Observe across at least one configured 15-second Prometheus scrape interval.
                long observationEnd = System.nanoTime() + observationWindow.toNanos();
                do {
                    checkpoint(id, false, deadline);
                    healthy(() -> checkpoint(id, dryRun, deadline));
                    if (System.nanoTime() >= observationEnd) break;
                    Thread.sleep(1000);
                } while (System.nanoTime() < observationEnd);
                checkpoint(id, false, deadline);
                safety.audit(id, actor, "POSTCHECK_PASSED", "Replicas, baseline and telemetry healthy");
                status = "RECOVERED";
                detail = "Fault completed and post-experiment steady state verified";
            }
        } catch (DeadlineExceeded ex) {
            status = "TIMED_OUT";
            detail = "Experiment deadline exceeded; review recovery before resuming";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            status = "ABORTED";
            detail = "Experiment interrupted; no further faults will be injected";
        } catch (RuntimeException ex) {
            status = attempted ? "ABORTED" : "FAILED";
            detail = attempted ? "Safety condition failed after injection; operator recovery review required" : "Safety precondition failed; no fault injected";
        }
        ExperimentResult result = new ExperimentResult(id, started, Instant.now(), type, target, status, elapsed, detail);
        // Atomic outcome + audit + release. Failure preserves the durable reservation.
        safety.finish(id, actor, status, attempted && !"RECOVERED".equals(status), () -> repository.save(result));
        return result;
    }
    private static class DeadlineExceeded extends RuntimeException {}
}
