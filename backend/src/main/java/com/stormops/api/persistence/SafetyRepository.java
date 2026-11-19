package com.stormops.api.persistence;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Repository
public class SafetyRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public SafetyRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(5);
    }
    public void begin(UUID id, String actor, String type, boolean dryRun) {
        transaction.executeWithoutResult(tx -> {
            int changed = jdbc.update("UPDATE safety_control SET active_run=? WHERE id=1 AND active_run IS NULL AND (NOT stopped OR ?)", id, dryRun);
            if (changed != 1) throw new IllegalStateException("Safety stop is active or an experiment requires completion/recovery");
            audit(id, actor, "STARTED", type + "; dryRun=" + dryRun);
        });
    }
    public void checkpoint(UUID id, boolean dryRun) {
        Boolean allowed = jdbc.queryForObject("SELECT active_run=? AND (NOT stopped OR ?) FROM safety_control WHERE id=1", Boolean.class, id, dryRun);
        if (!Boolean.TRUE.equals(allowed)) throw new IllegalStateException("Safety stop requested");
    }
    public void audit(UUID id, String actor, String event, String detail) {
        jdbc.update("INSERT INTO experiment_audit(run_id,actor,event,detail) VALUES (?,?,?,?)", id, actor, event, detail);
    }
    public void finish(UUID id, String actor, String status, boolean unsafe, Runnable saveResult) {
        transaction.executeWithoutResult(tx -> {
            saveResult.run();
            audit(id, actor, status, unsafe ? "Safety stop latched; operator review required" : "Experiment completed");
            int changed = jdbc.update("UPDATE safety_control SET active_run=NULL, stopped=stopped OR ? WHERE id=1 AND active_run=?", unsafe, id);
            if (changed != 1) throw new IllegalStateException("Experiment ownership lost");
        });
    }
    public void stop(boolean stopped, String actor) {
        transaction.executeWithoutResult(tx -> {
            int changed = jdbc.update("UPDATE safety_control SET stopped=? WHERE id=1 AND (? OR active_run IS NULL)", stopped, stopped);
            if (changed != 1) throw new IllegalStateException("Cannot resume during an active or interrupted experiment");
            audit(null, actor, stopped ? "STOP" : "RESUME", "Operator changed safety stop");
        });
    }
    public Map<String,Object> state() {
        return jdbc.queryForMap("SELECT stopped, active_run FROM safety_control WHERE id=1");
    }
    public List<Map<String,Object>> auditTrail() {
        return jdbc.queryForList("SELECT sequence,run_id,occurred_at,actor,event,detail FROM experiment_audit ORDER BY sequence DESC LIMIT 100");
    }
}
