package com.stormops.api.persistence;

import com.stormops.api.core.ExperimentResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExperimentRepository {

    private final JdbcTemplate jdbc;

    public ExperimentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(ExperimentResult result) {
        // Persist the immutable outcome using parameters rather than SQL interpolation.
        jdbc.update("""
                INSERT INTO experiments (
                    id, started_at, finished_at, experiment_type,
                    target, status, recovery_millis, detail
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                result.id(),
                Timestamp.from(result.startedAt()),
                Timestamp.from(result.finishedAt()),
                result.type(),
                result.target(),
                result.status(),
                result.recoveryMillis(),
                result.detail()
        );
    }

    public List<ExperimentResult> findRecent(int limit, int offset) {
        return jdbc.query("""
                SELECT id, started_at, finished_at, experiment_type,
                       target, status, recovery_millis, detail
                FROM experiments
                ORDER BY started_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, this::mapRow, limit, offset);
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM experiments", Long.class);
        return count == null ? 0 : count;
    }

    public ExperimentResult findById(UUID id) {
        List<ExperimentResult> results = jdbc.query("""
                SELECT id, started_at, finished_at, experiment_type,
                       target, status, recovery_millis, detail
                FROM experiments
                WHERE id = ?
                """, this::mapRow, id);
        return results.isEmpty() ? null : results.getFirst();
    }

    private ExperimentResult mapRow(ResultSet row, int index) throws SQLException {
        return new ExperimentResult(
                row.getObject("id", UUID.class),
                row.getTimestamp("started_at").toInstant(),
                row.getTimestamp("finished_at").toInstant(),
                row.getString("experiment_type"),
                row.getString("target"),
                row.getString("status"),
                row.getLong("recovery_millis"),
                row.getString("detail")
        );
    }
}
