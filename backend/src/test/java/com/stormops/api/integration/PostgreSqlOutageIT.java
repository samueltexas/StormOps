package com.stormops.api.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.github.dockerjava.api.DockerClient;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

 /**
  * Exercises a real PostgreSQL availability outage in an exclusively owned
  * Testcontainers database. It never touches the local StormOps database.
  */
class PostgreSqlOutageIT {

    @Test
    void temporaryDatabaseOutageDoesNotReleaseAnAmbiguousExperiment() {
        try (PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:17-alpine")) {
            database.start();

            // New physical connections avoid hiding the outage behind a pooled connection.
            String jdbcUrl = database.getJdbcUrl();
            String separator = jdbcUrl.contains("?") ? "&" : "?";
            var dataSource = new DriverManagerDataSource(
                    jdbcUrl + separator + "connectTimeout=2&socketTimeout=2",
                    database.getUsername(),
                    database.getPassword());
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            var jdbc = new JdbcTemplate(dataSource);
            var transactions = new DataSourceTransactionManager(dataSource);
            var safety = new com.stormops.api.persistence.SafetyRepository(jdbc, transactions);

            safety.stop(false, "test-operator");
            UUID activeRun = UUID.randomUUID();
            safety.begin(activeRun, "test-operator", "POD_TERMINATION", false);
            safety.audit(activeRun, "test-operator", "INJECTION_INTENT",
                    "Synthetic intent; no Kubernetes action performed");

            DockerClient docker = DockerClientFactory.instance().client();
            String ownedContainerId = database.getContainerId();

            // Pause only the disposable database created above, then always unpause.
            docker.pauseContainerCmd(ownedContainerId).exec();
            try {
                var inaccessible = new com.stormops.api.persistence.SafetyRepository(
                        new JdbcTemplate(dataSource),
                        new DataSourceTransactionManager(dataSource));
                assertThrows(RuntimeException.class,
                        () -> inaccessible.begin(UUID.randomUUID(), "other-operator", "BASELINE", false),
                        "An unavailable database must not admit a new experiment");
            } finally {
                docker.unpauseContainerCmd(ownedContainerId).exec();
            }

            // Reinitializing the repository simulates a restart after connectivity returns.
            var recovered = new com.stormops.api.persistence.SafetyRepository(
                    new JdbcTemplate(dataSource),
                    new DataSourceTransactionManager(dataSource));
            assertEquals(activeRun, recovered.state().get("active_run"));
            assertThrows(IllegalStateException.class,
                    () -> recovered.begin(UUID.randomUUID(), "other-operator", "BASELINE", false));
            assertEquals(List.of("STARTED", "INJECTION_INTENT"),
                    jdbc.queryForList(
                            "SELECT event FROM experiment_audit WHERE run_id = ? ORDER BY sequence",
                            String.class, activeRun));
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM experiments WHERE id = ?",
                    Long.class, activeRun));

            recovered.stop(true, "reviewer");
            assertEquals(true, recovered.state().get("stopped"));
            assertEquals(activeRun, recovered.state().get("active_run"));
            assertThrows(IllegalStateException.class,
                    () -> recovered.stop(false, "reviewer"),
                    "A reserved run must prevent automatic resumption");
        }
    }
}
