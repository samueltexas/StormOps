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

    private final Path kubeconfig = Path.of(
            System.getProperty("user.home"),
            "Projects", "StormOps", ".kube", "stormops-config"
    );

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
        String output = run("get", "pods", "-l", SELECTOR,
                "--field-selector=status.phase=Running",
                "-o", "jsonpath={range .items[*]}{.metadata.name}{'\\n'}{end}");

        return output.lines()
                .map(String::trim)
                .filter(name -> name.startsWith(DEPLOYMENT + "-"))
                .filter(name -> !name.isBlank())
                .sorted()
                .toList();
    }

    public boolean podExists(String podName) {
        return runningPods().contains(podName);
    }

    public void deletePod(String podName) {
        if (!podName.matches("stormops-demo-[a-z0-9-]+")) {
            throw new IllegalArgumentException("Invalid lab pod name");
        }

        if (!podExists(podName)) {
            throw new IllegalStateException("Selected pod is no longer running");
        }

        run("delete", "pod", podName, "--wait=false");
    }

    private String run(String... arguments) {
        if (!Files.isRegularFile(kubeconfig)) {
            throw new IllegalStateException("StormOps kubeconfig not found");
        }

        List<String> command = new ArrayList<>(List.of(
                "kubectl", "--kubeconfig", kubeconfig.toString(),
                "--context", CONTEXT, "--namespace", NAMESPACE
        ));
        command.addAll(List.of(arguments));

        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

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
                throw new IllegalStateException("Kubernetes operation failed: "
                        + output.strip());
            }

            return output;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot execute kubectl", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kubernetes operation interrupted", exception);
        }
    }
}
