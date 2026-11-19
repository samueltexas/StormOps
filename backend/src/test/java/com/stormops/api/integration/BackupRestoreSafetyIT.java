package com.stormops.api.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.stormops.api.core.ExperimentResult;
import com.stormops.api.persistence.ExperimentRepository;
import com.stormops.api.persistence.SafetyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;

/**
 * Verifies backup/restore using only two test-owned PostgreSQL containers.
 * Synthetic records and a nonexistent Kubernetes operation keep it non-destructive.
 */
class BackupRestoreSafetyIT {
    private static final String DUMP_PATH = "/tmp/stormops-restore-test.dump";

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void restoredDatabaseRetainsAuditHistoryAndAmbiguousReservation() throws Exception {
        try (PostgreSQLContainer<?> source = new PostgreSQLContainer<>("postgres:17-alpine");
             PostgreSQLContainer<?> destination = new PostgreSQLContainer<>("postgres:17-alpine")) {
            source.start();

            var sourceData = new DriverManagerDataSource(
                    source.getJdbcUrl(), source.getUsername(), source.getPassword());
            Flyway.configure()
                    .dataSource(sourceData)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            var sourceJdbc = new JdbcTemplate(sourceData);
            var originalSafety = new SafetyRepository(
                    sourceJdbc, new DataSourceTransactionManager(sourceData));
            var originalExperiments = new ExperimentRepository(sourceJdbc);

            UUID completedId = UUID.randomUUID();
            Instant started = Instant.parse("2026-01-01T12:00:00Z");
            var completed = new ExperimentResult(
                    completedId, started, started.plusSeconds(4),
                    "BASELINE", "synthetic-target", "RECOVERED",
                    4000L, "Disposable completed test");
            originalExperiments.save(completed);

            // An unfinished injection intent is ambiguous, not evidence of recovery.
            originalSafety.stop(false, "backup-test");
            UUID activeRun = UUID.randomUUID();
            originalSafety.begin(activeRun, "backup-test", "POD_TERMINATION", false);
            originalSafety.audit(activeRun, "backup-test", "INJECTION_INTENT",
                    "Synthetic intent; no Kubernetes request issued");
            originalSafety.stop(true, "reviewer");

            Map<String, Object> originalState = originalSafety.state();
            List<Map<String, Object>> originalAudit = sourceJdbc.queryForList(
                    "SELECT sequence, run_id, occurred_at, actor, event, detail "
                            + "FROM experiment_audit ORDER BY sequence");
            Long lastSequence = sourceJdbc.queryForObject(
                    "SELECT MAX(sequence) FROM experiment_audit", Long.class);
            assertNotNull(lastSequence);
            assertEquals(true, originalState.get("stopped"));
            assertEquals(activeRun, originalState.get("active_run"));
            assertNull(originalExperiments.findById(activeRun));

            // Archive schema, data and owned sequence values with PostgreSQL tools.
            var backup = source.execInContainer(
                    "pg_dump", "-Fc", "--no-owner", "--no-privileges",
                    "-U", source.getUsername(), "-d", source.getDatabaseName(),
                    "-f", DUMP_PATH);
            assertEquals(0, backup.getExitCode(), "Disposable pg_dump must succeed");
            byte[] bytes = source.copyFileFromContainer(
                    DUMP_PATH, input -> input.readAllBytes());
            assertTrue(bytes.length > 1024, "The backup archive is unexpectedly small");

            destination.start();
            destination.copyFileToContainer(Transferable.of(bytes, 0600), DUMP_PATH);
            var restore = destination.execInContainer(
                    "pg_restore", "--exit-on-error", "--no-owner", "--no-privileges",
                    "-U", destination.getUsername(), "-d", destination.getDatabaseName(),
                    DUMP_PATH);
            assertEquals(0, restore.getExitCode(), "Disposable pg_restore must succeed");

            var restoredData = new DriverManagerDataSource(
                    destination.getJdbcUrl(), destination.getUsername(),
                    destination.getPassword());
            var jdbc = new JdbcTemplate(restoredData);
            var safety = new SafetyRepository(
                    jdbc, new DataSourceTransactionManager(restoredData));
            var experiments = new ExperimentRepository(jdbc);

            assertEquals(originalState, safety.state());
            assertEquals(completed, experiments.findById(completedId));
            assertNull(experiments.findById(activeRun),
                    "The restored database must not fabricate a terminal outcome");
            assertEquals(originalAudit, jdbc.queryForList(
                    "SELECT sequence, run_id, occurred_at, actor, event, detail "
                            + "FROM experiment_audit ORDER BY sequence"));
            assertEquals(1L, experiments.count());
            assertEquals(2L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE",
                    Long.class));

            // Restoring must not make it possible to inject or resume.
            assertThrows(IllegalStateException.class,
                    () -> safety.begin(UUID.randomUUID(), "reviewer", "BASELINE", false));
            assertThrows(IllegalStateException.class,
                    () -> safety.stop(false, "reviewer"));
            assertEquals(originalState, safety.state());

            // The BIGSERIAL sequence must still advance after restored history.
            safety.audit(null, "restore-test", "RESTORE_VERIFIED",
                    "Verification only; stop remains latched");
            Long nextSequence = jdbc.queryForObject(
                    "SELECT MAX(sequence) FROM experiment_audit", Long.class);
            assertNotNull(nextSequence);
            assertTrue(nextSequence > lastSequence);
            assertEquals(originalAudit.size() + 1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM experiment_audit", Long.class));
            assertEquals(originalState, safety.state());
        }
    }
}
