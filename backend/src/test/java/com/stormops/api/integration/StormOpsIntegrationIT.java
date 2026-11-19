package com.stormops.api.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.stormops.api.core.ExperimentResult;
import com.stormops.api.persistence.ExperimentRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Real HTTP + Flyway + PostgreSQL integration tests. Never connects to the local StormOps DB. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            "stormops.security.admin.username=admin",
            "stormops.security.admin.password=stormops-integration-admin-only",
            "stormops.security.viewer.username=viewer",
            "stormops.security.viewer.password=stormops-integration-viewer-only",
            "spring.datasource.hikari.maximum-pool-size=2"
        })
class StormOpsIntegrationIT {
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("stormops_integration")
                    .withUsername("integration")
                    .withPassword("integration-only");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExperimentRepository repository;
    @Autowired com.stormops.api.persistence.SafetyRepository safety;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @Test void concurrentAdmissionHasExactlyOneWinner() throws Exception {
        safety.stop(false, "integration");
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> attempt = () -> {
                start.await();
                try { safety.begin(UUID.randomUUID(), "integration", "BASELINE", false); return true; }
                catch (IllegalStateException rejected) { return false; }
            };
            var first = pool.submit(attempt);
            var second = pool.submit(attempt);
            start.countDown();
            assertNotEquals(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            jdbc.update("UPDATE safety_control SET active_run=NULL, stopped=TRUE WHERE id=1");
        }
    }


    @Test void durableReservationSurvivesAnotherRepositoryAndBlocksResume() {
        safety.stop(false, "integration");
        UUID id = UUID.randomUUID();
        try {
            safety.begin(id, "integration", "BASELINE", false);
            var restarted = new com.stormops.api.persistence.SafetyRepository(new JdbcTemplate(jdbc.getDataSource()), transactions);
            assertThrows(IllegalStateException.class, () -> restarted.begin(UUID.randomUUID(), "integration", "BASELINE", false));
            safety.stop(true, "integration");
            assertThrows(IllegalStateException.class, () -> safety.checkpoint(id, false));
            assertThrows(IllegalStateException.class, () -> safety.stop(false, "integration"));
            assertThrows(IllegalStateException.class, () -> safety.finish(id, "integration", "FAILED", true, () -> { throw new IllegalStateException(); }));
            assertEquals(id, safety.state().get("active_run"));
            safety.finish(id, "integration", "ABORTED", true, () -> {});
            assertEquals(true, safety.state().get("stopped"));
            assertNull(safety.state().get("active_run"));
        } finally {
            jdbc.update("UPDATE safety_control SET active_run=NULL, stopped=TRUE WHERE id=1");
        }
    }
    @Test void viewerCannotChangeSafetyControls() throws Exception {
        assertEquals(403, request("POST", "/api/v1/safety/resume", "viewer", "stormops-integration-viewer-only", "RESUME_STORMOPS", null).statusCode());
    }


    private HttpResponse<String> request(String method, String path, String user, String password,
                                         String confirmation, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (user != null) {
            var credentials = Base64.getEncoder().encodeToString(
                    (user + ":" + password).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + credentials);
        }
        if (confirmation != null) builder.header("X-StormOps-Confirmation", confirmation);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void flywayCreatesExpectedSchema() {
        Integer migrations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertNotNull(migrations);
        assertTrue(migrations >= 1);
        Integer table = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='experiments'",
                Integer.class);
        assertEquals(1, table);
    }

    @Test void repositoryPersistsAndReadsWithoutTouchingProduction() {
        UUID id = UUID.randomUUID();
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        var record = new ExperimentResult(id, start, start.plusMillis(42), "BASELINE", "integration-test",
                "RECOVERED", 42L, "Test-only record");
        try {
            repository.save(record);
            var loaded = repository.findById(id);
            assertNotNull(loaded);
            assertEquals(id, loaded.id());
            assertEquals(42L, loaded.recoveryMillis());
        } finally {
            jdbc.update("DELETE FROM experiments WHERE id = ?", id);
        }
    }

    @Test void anonymousCannotReadProtectedApi() throws Exception {
        assertEquals(401, request("GET", "/api/v1/auth/me", null, null, null, null).statusCode());
        assertEquals(401, request("GET", "/api/v1/analytics/summary", null, null, null, null).statusCode());
    }

    @Test void viewerCanReadButCannotExecuteEitherFault() throws Exception {
        assertEquals(200, request("GET", "/api/v1/auth/me", "viewer", "stormops-integration-viewer-only", null, null).statusCode());
        assertEquals(200, request("GET", "/api/v1/reliability/report", "viewer", "stormops-integration-viewer-only", null, null).statusCode());
        assertEquals(403, request("POST", "/api/v1/experiments/pod-termination", "viewer", "stormops-integration-viewer-only",
                "TERMINATE_STORMOPS_DEMO_POD", null).statusCode());
        assertEquals(403, request("POST", "/api/v1/experiments/http-simulation", "viewer", "stormops-integration-viewer-only",
                "RUN_STORMOPS_HTTP_SIMULATION", "{\"type\":\"BASELINE\",\"latencyMillis\":0}").statusCode());
    }

    @Test void adminCanReadAndInvalidConfirmationDoesNotExecuteFault() throws Exception {
        var me = request("GET", "/api/v1/auth/me", "admin", "stormops-integration-admin-only", null, null);
        assertEquals(200, me.statusCode());
        assertTrue(me.body().contains("ADMIN"));
        assertEquals(400, request("POST", "/api/v1/experiments/pod-termination", "admin", "stormops-integration-admin-only",
                "INVALID_CONFIRMATION", null).statusCode());
    }

    @Test void analyticsAndReliabilityReturnValidReports() throws Exception {
        var analytics = request("GET", "/api/v1/analytics/summary", "viewer", "stormops-integration-viewer-only", null, null);
        assertEquals(200, analytics.statusCode());
        assertTrue(analytics.body().contains("\"total\""));
        var reliability = request("GET", "/api/v1/reliability/report?podRecoveryThresholdMillis=15000&httpResponseThresholdMillis=400",
                "viewer", "stormops-integration-viewer-only", null, null);
        assertEquals(200, reliability.statusCode());
        assertTrue(reliability.body().contains("\"evaluations\""));
    }
}
