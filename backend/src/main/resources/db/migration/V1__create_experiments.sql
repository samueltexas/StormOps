-- Preserve completed experiments across API restarts.
CREATE TABLE experiments (
    id UUID PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL,
    experiment_type VARCHAR(64) NOT NULL,
    target VARCHAR(253) NOT NULL,
    status VARCHAR(32) NOT NULL,
    recovery_millis BIGINT NOT NULL,
    detail TEXT NOT NULL,
    CONSTRAINT chk_recovery_millis CHECK (recovery_millis >= -1),
    CONSTRAINT chk_experiment_time CHECK (finished_at >= started_at)
);

CREATE INDEX idx_experiments_started_at
    ON experiments (started_at DESC, id DESC);
