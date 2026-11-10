package com.stormops.api.core;

import com.stormops.api.kubernetes.KubernetesGateway;
import com.stormops.api.persistence.ExperimentRepository;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

@Service
public class ExperimentService {

    private static final int REQUIRED_REPLICAS = 2;
    private static final Duration RECOVERY_TIMEOUT = Duration.ofSeconds(90);

    private final KubernetesGateway kubernetes;
    private final ExperimentRepository repository;
    private final HttpSimulationGateway httpSimulations;
    private final ReentrantLock experimentLock = new ReentrantLock();

    /** Retain the existing constructor for Phase 4/5 unit tests. */
    public ExperimentService(KubernetesGateway kubernetes, ExperimentRepository repository) {
        this(kubernetes, repository, new HttpSimulationGateway());
    }

    @Autowired
    public ExperimentService(KubernetesGateway kubernetes,
                             ExperimentRepository repository,
                             HttpSimulationGateway httpSimulations) {
        this.kubernetes = kubernetes;
        this.repository = repository;
        this.httpSimulations = httpSimulations;
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

    public ExperimentResult terminateOnePod() {
        // Serialize destructive experiments against the same demo deployment.
        if (!experimentLock.tryLock()) {
            throw new IllegalStateException("An experiment is already running");
        }

        UUID id = UUID.randomUUID();
        Instant startedAt = Instant.now();
        String target = "stormops-demo";
        String status = "FAILED";
        String detail = "Experiment did not complete";
        long recoveryMillis = -1;

        try {
            int desired = kubernetes.desiredReplicas();
            int ready = kubernetes.readyReplicas();
            List<String> pods = kubernetes.runningPods();

            // Do not inject a failure when the deployment has reduced redundancy.
            if (desired != REQUIRED_REPLICAS
                    || ready != REQUIRED_REPLICAS
                    || pods.size() != REQUIRED_REPLICAS) {
                throw new IllegalStateException(
                        "Safety check failed: exactly two healthy replicas are required"
                );
            }

            target = pods.getFirst();
            kubernetes.deletePod(target);
            Instant deletionRequestedAt = Instant.now();
            Instant deadline = deletionRequestedAt.plus(RECOVERY_TIMEOUT);

            while (Instant.now().isBefore(deadline)) {
                boolean oldPodGone = !kubernetes.podExists(target);
                boolean replicasReady = kubernetes.readyReplicas() == REQUIRED_REPLICAS;

                if (oldPodGone && replicasReady) {
                    recoveryMillis = Duration.between(
                            deletionRequestedAt, Instant.now()
                    ).toMillis();
                    status = "RECOVERED";
                    detail = "Replacement pod observed and desired replicas restored";
                    break;
                }

                Thread.sleep(2000);
            }

            if (!"RECOVERED".equals(status)) {
                status = "TIMED_OUT";
                detail = "Deployment did not recover within 90 seconds";
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            detail = "Experiment interrupted";
        } catch (RuntimeException exception) {
            detail = exception.getMessage() == null
                    ? "Experiment failed unexpectedly"
                    : exception.getMessage();
        } finally {
            experimentLock.unlock();
        }

        ExperimentResult result = new ExperimentResult(
                id, startedAt, Instant.now(), "POD_TERMINATION",
                target, status, recoveryMillis, detail
        );

        // Database failures must remain visible to the caller, not masquerade as success.
        repository.save(result);
        return result;
    }

    /** Runs one bounded, request-scoped HTTP simulation in the fixed lab target. */
    public ExperimentResult runHttpSimulation(String type, int latencyMillis) {
        if (!List.of("BASELINE", "HTTP_LATENCY", "HTTP_ERROR").contains(type)) {
            throw new IllegalArgumentException("Unsupported HTTP experiment type");
        }
        if (latencyMillis < 0 || latencyMillis > 2000) {
            throw new IllegalArgumentException("Latency must be between 0 and 2000 milliseconds");
        }
        if (!"HTTP_LATENCY".equals(type) && latencyMillis != 0) {
            throw new IllegalArgumentException("Latency applies only to HTTP_LATENCY");
        }
        if (!experimentLock.tryLock()) {
            throw new IllegalStateException("An experiment is already running");
        }

        UUID id = UUID.randomUUID();
        Instant startedAt = Instant.now();
        String status = "FAILED";
        String detail = "HTTP experiment did not complete";
        long elapsedMillis = -1;

        try {
            HttpSimulationGateway.Observation observation =
                    httpSimulations.invoke(type, latencyMillis);
            elapsedMillis = observation.elapsedMillis();
            int expectedStatus = "HTTP_ERROR".equals(type) ? 503 : 200;
            if (observation.statusCode() == expectedStatus) {
                status = "RECOVERED";
                detail = "Expected HTTP " + expectedStatus
                        + " observed; elapsed=" + elapsedMillis + "ms"
                        + ("HTTP_LATENCY".equals(type)
                        ? "; injected=" + latencyMillis + "ms" : "");
            } else {
                detail = "Unexpected HTTP " + observation.statusCode()
                        + "; expected=" + expectedStatus;
            }
        } catch (RuntimeException exception) {
            detail = exception.getMessage() == null
                    ? "HTTP simulation failed unexpectedly"
                    : exception.getMessage();
        } finally {
            experimentLock.unlock();
        }

        // This schema stores recovery_millis as a nonnegative measurement.
        // For HTTP simulations the field contains observed request duration,
        // not Kubernetes replica recovery time. The detail states this explicitly.
        ExperimentResult result = new ExperimentResult(
                id, startedAt, Instant.now(), type,
                "stormops-demo", status, elapsedMillis, detail);
        repository.save(result);
        return result;
    }
}
