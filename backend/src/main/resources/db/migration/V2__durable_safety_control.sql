-- The singleton never expires: crashes cannot silently permit another injection.
CREATE TABLE safety_control (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    stopped BOOLEAN NOT NULL DEFAULT TRUE,
    active_run UUID
);
INSERT INTO safety_control (id, stopped) VALUES (1, TRUE);
CREATE TABLE experiment_audit (
    sequence BIGSERIAL PRIMARY KEY,
    run_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor VARCHAR(253) NOT NULL,
    event VARCHAR(64) NOT NULL,
    detail TEXT NOT NULL
);
CREATE INDEX idx_experiment_audit_run ON experiment_audit(run_id, sequence);
