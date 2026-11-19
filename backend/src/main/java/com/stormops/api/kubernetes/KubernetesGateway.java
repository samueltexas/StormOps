package com.stormops.api.kubernetes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * Restricts Kubernetes access to a fixed context, namespace and workload.
 * No caller-controlled shell commands or resource names are accepted.
 */
@Component
public class KubernetesGateway {

    private static final String CONTEXT = "kind-stormops-local";
    private static final String NAMESPACE = "stormops-lab";
    private static final String DEPLOYMENT = "stormops-demo";
    private static final String SELECTOR = "app=stormops-demo";

    private final Path kubeconfig;

    public KubernetesGateway() { this(defaultKubeconfig()); }

    @org.springframework.beans.factory.annotation.Autowired
    public KubernetesGateway(@org.springframework.beans.factory.annotation.Value("${stormops.kubernetes.kubeconfig}") String config) {
        this.kubeconfig = Path.of(config);
    }

    private static String defaultKubeconfig() { return Path.of(
            System.getProperty("user.home"),
            "Projects", "StormOps", ".kube", "stormops-runner-config"
    ).toString(); }

    public int desiredReplicas() {
        return Integer.parseInt(run("get", "deployment", DEPLOYMENT,
                "-o", "jsonpath={.spec.replicas}").trim());
    }

    public int readyReplicas() {
        String value = run("get", "deployment", DEPLOYMENT,
                "-o", "jsonpath={.status.readyReplicas}").trim();
        return value.isEmpty() ? 0 : Integer.parseInt(value);
    }

    public List<String> runningPods() {
        String output = run("get", "pods", "-l", SELECTOR, "-o", "json");
        return eligiblePods(output);
    }

    static List<String> eligiblePods(String json) {
        var root = tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);
        List<String> names = new ArrayList<>();
        for (var pod : root.path("items")) {
            var metadata = pod.path("metadata");
            String name = metadata.path("name").asText();
            boolean ready = false;
            for (var condition : pod.path("status").path("conditions")) {
                if ("Ready".equals(condition.path("type").asText()) && "True".equals(condition.path("status").asText())) ready = true;
            }
            boolean managed = false;
            for (var owner : metadata.path("ownerReferences")) {
                if (owner.path("controller").asBoolean() && "ReplicaSet".equals(owner.path("kind").asText())
                        && owner.path("name").asText().startsWith(DEPLOYMENT + "-")) managed = true;
            }
            if (NAMESPACE.equals(metadata.path("namespace").asText())
                    && "stormops-demo".equals(metadata.path("labels").path("app").asText())
                    && name.matches("stormops-demo-[a-z0-9-]+")
                    && !metadata.hasNonNull("deletionTimestamp") && managed && ready
                    && "Running".equals(pod.path("status").path("phase").asText())) names.add(name);
        }
        return names.stream().sorted().toList();
    }

    public boolean podExists(String podName) {
        return !run("get", "pod", podName, "--ignore-not-found", "-o", "name").isBlank();
    }

    public void deletePod(String podName) {
        if (!podName.matches("stormops-demo-[a-z0-9-]+")) {
            throw new IllegalArgumentException("Invalid lab pod name");
        }

        String snapshot = run("get", "pod", podName, "-o", "json");
        if (!eligiblePods("{\"items\":[" + snapshot + "]}").contains(podName)) {
            throw new IllegalStateException("Selected pod is no longer eligible");
        }
        String uid = tools.jackson.databind.json.JsonMapper.builder().build().readTree(snapshot)
                .path("metadata").path("uid").asText();
        if (!uid.matches("[a-f0-9-]{36}")) throw new IllegalStateException("Invalid pod identity");
        // UID preconditions prevent deleting a replacement that reuses the selected name.
        String options = "{\"apiVersion\":\"v1\",\"kind\":\"DeleteOptions\",\"preconditions\":{\"uid\":\"" + uid + "\"}}";
        runWithInput(options, "delete", "--raw", "/api/v1/namespaces/" + NAMESPACE + "/pods/" + podName, "-f", "-");
    }

    private String run(String... arguments) { return runWithInput(null, arguments); }

    private String runWithInput(String input, String... arguments) {
        if (!Files.isRegularFile(kubeconfig)) {
            throw new IllegalStateException("StormOps kubeconfig not found");
        }

        List<String> command = new ArrayList<>(List.of(
                "kubectl", "--kubeconfig", kubeconfig.toString(),
                "--context", CONTEXT, "--namespace", NAMESPACE, "--request-timeout=10s"
        ));
        command.addAll(List.of(arguments));

        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            try (var stdin = process.getOutputStream()) {
                if (input != null) stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }

            // Bound subprocess execution to prevent a stalled Kubernetes API
            // from indefinitely blocking the experiment worker.
            boolean completed = process.waitFor(Duration.ofSeconds(12).toMillis(),
                    TimeUnit.MILLISECONDS);

            if (!completed) {
                process.destroyForcibly();
                throw new IllegalStateException("Kubernetes command timed out");
            }

            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);

            if (process.exitValue() != 0) {
                throw new IllegalStateException("Kubernetes operation failed");
            }

            return output;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot execute kubectl", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kubernetes operation interrupted", exception);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
