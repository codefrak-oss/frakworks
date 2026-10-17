-- One row per collected Smith run (engine/README.md, "Smith runs"): what the
-- sandbox used, so an admission decision can rest on data. `peak_bytes` is the
-- container's cgroup v2 memory.peak, null when it could not be read.
CREATE TABLE engine.smith_run_stats (
    run_id          VARCHAR(64) PRIMARY KEY,
    root_flow_kind  VARCHAR(255),
    repo            VARCHAR(255) NOT NULL,
    issue           BIGINT NOT NULL,
    tier            INTEGER,
    model           VARCHAR(100),
    effort          VARCHAR(20),
    account         VARCHAR(255),
    started         TIMESTAMP WITH TIME ZONE,
    ended           TIMESTAMP WITH TIME ZONE NOT NULL,
    exit_code       BIGINT NOT NULL,
    peak_bytes      BIGINT,
    oom_killed      BOOLEAN
);
