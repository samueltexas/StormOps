package com.stormops.api.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.stormops.api.persistence.ExperimentRepository;
import com.stormops.api.persistence.SafetyRepository;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/**
 * Injects a TCP partition through an isolated Testcontainers network.
 * All databases, aliases and toxics are created and destroyed by this test.
 */
class DatabaseNetworkPartitionIT {
    private static final String DATABASE_ALIAS = "stormops-v3-test-db";
    private static final int PROXY_PORT = 8666;

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void unreachableDatabaseDoesNotLoseOrReopenAmbiguousRun() throws Exception {
        try (Network network = Network.newNetwork();
             PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:17-alpine")
                     .withNetwork(network)
                     .withNetworkAliases(DATABASE_ALIAS);
             ToxiproxyContainer toxiproxy =
                     new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0")
                             .withNetwork(network)) {
            database.start();
            toxiproxy.start();

            var control = new ToxiproxyClient(toxiproxy.getHost(),
                    toxiproxy.getControlPort());
            Proxy proxy = control.createProxy(
                    "stormops-v3-postgres", "0.0.0.0:" + PROXY_PORT,
                    DATABASE_ALIAS + ":5432");

            // The repository accesses PostgreSQL only through the dedicated proxy.
            String jdbcUrl = "jdbc:postgresql://" + toxiproxy.getHost() + ":"
                    + toxiproxy.getMappedPort(PROXY_PORT)
                    + "/" + database.getDatabaseName()
                    + "?connectTimeout=2&socketTimeout=2";
            var dataSource = new DriverManagerDataSource(
                    jdbcUrl, database.getUsername(), database.getPassword());
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            var jdbc = new JdbcTemplate(dataSource);
            var transactions = new DataSourceTransactionManager(dataSource);
            var safety = new SafetyRepository(jdbc, transactions);
            var experiments = new ExperimentRepository(jdbc);

            safety.stop(false, "network-test");
            UUID activeRun = UUID.randomUUID();
            safety.begin(activeRun, "network-test", "POD_TERMINATION", false);
            safety.audit(activeRun, "network-test", "INJECTION_INTENT",
                    "Synthetic marker; no Kubernetes operation issued");

            List<String> baselineAudit = auditEvents(jdbc, activeRun);
            assertEquals(List.of("STARTED", "INJECTION_INTENT"), baselineAudit);
            assertEquals(activeRun, safety.state().get("active_run"));

            // Keep the test database healthy while isolating its client path.
            // A 2s JDBC socket timeout bounds an intentionally blackholed request.
            proxy.toxics().bandwidth(
                    "CUT_NETWORK_DOWNSTREAM", ToxicDirection.DOWNSTREAM, 0);
            try {
                proxy.toxics().bandwidth(
                        "CUT_NETWORK_UPSTREAM", ToxicDirection.UPSTREAM, 0);
                try {
                    assertThrows(RuntimeException.class,
                            () -> jdbc.queryForObject("SELECT 1", Integer.class),
                            "JDBC must fail through the severed test-only proxy");
                    assertThrows(RuntimeException.class,
                            () -> safety.begin(UUID.randomUUID(), "other-operator",
                                    "BASELINE", false),
                            "No admission should succeed while the network is partitioned");

                    // Direct access is a diagnostic only: the DB process stays healthy.
                    var direct = new JdbcTemplate(new DriverManagerDataSource(
                            database.getJdbcUrl(), database.getUsername(),
                            database.getPassword()));
                    assertEquals(1, direct.queryForObject("SELECT 1", Integer.class));
                } finally {
                    proxy.toxics().get("CUT_NETWORK_UPSTREAM").remove();
                }
            } finally {
                proxy.toxics().get("CUT_NETWORK_DOWNSTREAM").remove();
            }

            // After connectivity returns, durable exclusion still prevents reinjection.
            var recovered = new SafetyRepository(jdbc, transactions);
            assertEquals(activeRun, recovered.state().get("active_run"));
            assertEquals(false, recovered.state().get("stopped"));
            assertEquals(baselineAudit, auditEvents(jdbc, activeRun));
            assertNull(experiments.findById(activeRun),
                    "A network outage must not fabricate a terminal outcome");
            assertThrows(IllegalStateException.class,
                    () -> recovered.begin(UUID.randomUUID(), "other-operator",
                            "POD_TERMINATION", false));
            assertThrows(IllegalStateException.class,
                    () -> recovered.stop(false, "other-operator"));

            recovered.stop(true, "reviewer");
            assertEquals(true, recovered.state().get("stopped"));
            assertEquals(activeRun, recovered.state().get("active_run"));
            assertEquals(baselineAudit, auditEvents(jdbc, activeRun));
        }
    }

    private static List<String> auditEvents(JdbcTemplate jdbc, UUID runId) {
        return jdbc.queryForList(
                "SELECT event FROM experiment_audit WHERE run_id=? ORDER BY sequence",
                String.class, runId);
    }
}
