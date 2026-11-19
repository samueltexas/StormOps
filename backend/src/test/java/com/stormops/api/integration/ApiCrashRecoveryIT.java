package com.stormops.api.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Crashes only a child JVM started by this test. Both API instances use an
 * exclusive Testcontainers database and an intentionally missing kubeconfig.
 */
class ApiCrashRecoveryIT {
    private static final Duration STARTUP_BUDGET = Duration.ofSeconds(45);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .build();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void crashWithAmbiguousIntentDoesNotUnlockOrReinjectOnRestart() throws Exception {
        Path applicationJar = Path.of("target", "stormops-api.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(applicationJar),
                "Build the application package before running the integration profile");

        Path isolation = Files.createTempDirectory("stormops-crash-it-");
        Process first = null;
        Process second = null;

        try (PostgreSQLContainer<?> database =
                     new PostgreSQLContainer<>("postgres:17-alpine")) {
            database.start();

            var dataSource = new DriverManagerDataSource(
                    database.getJdbcUrl(), database.getUsername(), database.getPassword());
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            var jdbc = new JdbcTemplate(dataSource);
            var safety = new com.stormops.api.persistence.SafetyRepository(
                    jdbc, new DataSourceTransactionManager(dataSource));

            // Use distinct credentials and a loopback-only port for this one test.
            String adminPassword = UUID.randomUUID().toString();
            String viewerPassword = UUID.randomUUID().toString();
            int port = unusedLoopbackPort();

            first = launch(applicationJar, database, isolation, port,
                    adminPassword, viewerPassword, "first");
            awaitReady(first, port, adminPassword);

            // No real experiment is executed; this emulates a persisted intent
            // whose external outcome would be unknown after a process crash.
            safety.stop(false, "crash-test");
            UUID activeRun = UUID.randomUUID();
            safety.begin(activeRun, "crash-test", "POD_TERMINATION", false);
            safety.audit(activeRun, "crash-test", "INJECTION_INTENT",
                    "Synthetic crash marker; no Kubernetes request issued");

            var before = request(port, adminPassword, "GET", "/api/v1/safety", null);
            assertEquals(200, before.statusCode());
            assertEquals(activeRun.toString(),
                    JSON.readTree(before.body()).path("active_run").asText());

            // Kill exactly the JVM created by this test, never a local API PID.
            terminateOwnedProcess(first);
            first = null;

            second = launch(applicationJar, database, isolation, port,
                    adminPassword, viewerPassword, "second");
            awaitReady(second, port, adminPassword);

            var snapshot = request(port, adminPassword, "GET", "/api/v1/safety", null);
            assertEquals(200, snapshot.statusCode());
            var state = JSON.readTree(snapshot.body());
            assertEquals(activeRun.toString(), state.path("active_run").asText());

            // A retained reservation blocks both accidental reinjection and resume.
            assertEquals(409, request(port, adminPassword, "POST",
                    "/api/v1/experiments/pod-termination?dryRun=false",
                    "TERMINATE_STORMOPS_DEMO_POD").statusCode());
            assertEquals(409, request(port, adminPassword, "POST",
                    "/api/v1/safety/resume", "RESUME_STORMOPS").statusCode());

            // An operator can engage stop without silently clearing the run.
            assertEquals(200, request(port, adminPassword, "POST",
                    "/api/v1/safety/stop", "STOP_STORMOPS").statusCode());
            var stopped = JSON.readTree(request(port, adminPassword, "GET",
                    "/api/v1/safety", null).body());
            assertTrue(stopped.path("stopped").asBoolean());
            assertEquals(activeRun.toString(), stopped.path("active_run").asText());

            assertEquals(List.of("STARTED", "INJECTION_INTENT"),
                    jdbc.queryForList(
                            "SELECT event FROM experiment_audit WHERE run_id=? ORDER BY sequence",
                            String.class, activeRun));
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM experiments WHERE id=?", Long.class, activeRun));
        } finally {
            terminateOwnedProcess(second);
            terminateOwnedProcess(first);
            Files.deleteIfExists(isolation.resolve("first.log"));
            Files.deleteIfExists(isolation.resolve("second.log"));
            Files.deleteIfExists(isolation);
        }
    }

    private static int unusedLoopbackPort() throws IOException {
        try (var socket = new ServerSocket(0, 0,
                InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static Process launch(
            Path jar, PostgreSQLContainer<?> database, Path isolation, int port,
            String adminPassword, String viewerPassword, String label) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var builder = new ProcessBuilder(java, "-jar", jar.toString(),
                "--server.address=127.0.0.1",
                "--server.port=" + port,
                "--spring.main.banner-mode=off");
        builder.environment().keySet().removeIf(key ->
                key.startsWith("STORMOPS_")
                        || key.startsWith("SPRING_")
                        || key.startsWith("SERVER_")
                        || key.equals("JAVA_TOOL_OPTIONS")
                        || key.equals("JDK_JAVA_OPTIONS"));
        var env = builder.environment();
        env.put("STORMOPS_DB_URL", database.getJdbcUrl());
        env.put("STORMOPS_DB_USER", database.getUsername());
        env.put("STORMOPS_DB_PASSWORD", database.getPassword());
        env.put("STORMOPS_ADMIN_USERNAME", "admin");
        env.put("STORMOPS_ADMIN_PASSWORD", adminPassword);
        env.put("STORMOPS_VIEWER_USERNAME", "viewer");
        env.put("STORMOPS_VIEWER_PASSWORD", viewerPassword);
        env.put("STORMOPS_SECURITY_ENABLED", "true");
        env.put("STORMOPS_KUBECONFIG",
                isolation.resolve("nonexistent-runner-kubeconfig").toString());
        builder.redirectErrorStream(true);
        builder.redirectOutput(isolation.resolve(label + ".log").toFile());
        return builder.start();
    }

    private static void awaitReady(
            Process process, int port, String password) throws Exception {
        long deadline = System.nanoTime() + STARTUP_BUDGET.toNanos();
        while (System.nanoTime() < deadline) {
            assertTrue(process.isAlive(), "Isolated API JVM exited before startup");
            try {
                var response = request(port, password, "GET", "/api/v1/auth/me", null);
                if (response.statusCode() == 200
                        && "admin".equals(JSON.readTree(response.body())
                                .path("username").asText())) {
                    return;
                }
            } catch (IOException ignored) {
                // Startup has not bound the loopback socket yet.
            }
            Thread.sleep(200);
        }
        fail("Isolated API did not become healthy within the startup budget");
    }

    private static HttpResponse<String> request(
            int port, String password, String method, String path, String confirmation)
            throws IOException, InterruptedException {
        String token = Base64.getEncoder().encodeToString(
                ("admin:" + password).getBytes(StandardCharsets.UTF_8));
        var builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Basic " + token);
        if (confirmation != null) {
            builder.header("X-StormOps-Confirmation", confirmation);
        }
        return HTTP.send(
                builder.method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void terminateOwnedProcess(Process process) throws InterruptedException {
        if (process == null) {
            return;
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        assertTrue(process.waitFor(15, TimeUnit.SECONDS),
                "An isolated API JVM did not exit after termination");
    }
}
