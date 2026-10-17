-- One row per job run (#1653; engine/README.md, "Custom jobs and the SDK", "Refactoring jobs"): what GET /jobs/{id}/runs
-- answers. Written by the job flow's noteJobStarted / noteJobEnded; a refactor run's scan (bpmn/refactor-scan.bpmn) adds
-- what it filed and skipped to the run that filed it. No foreign key to engine.job: the history outlives a deleted job.
CREATE TABLE engine.job_run (
    process_instance VARCHAR(64)  PRIMARY KEY,        -- the job flow's instance
    job_id           VARCHAR(36)  NOT NULL,
    repo             VARCHAR(200) NOT NULL,
    kind             VARCHAR(10)  NOT NULL,
    started_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at         TIMESTAMP WITH TIME ZONE,
    status           VARCHAR(10)  CHECK (status IN ('done', 'nothing', 'failed')), -- null while it runs
    outcome          TEXT,                            -- the flow's outcome variable (`done`, `failed: ...`)
    summary          TEXT,                            -- the flow's summary: what it filed, or why nothing
    scan_issue       BIGINT,                          -- a refactor run's refactor-scan ticket
    scan_ended_at    TIMESTAMP WITH TIME ZONE,
    scan_outcome     TEXT,                            -- the scan's outcome (`done`, `failed`, `failed: ...`)
    scan_summary     TEXT,                            -- the scan's summary (`filed #a, #b; skipped 1 open`)
    filed            TEXT,                            -- JSON array of the ticket numbers the scan filed
    skipped          TEXT,                            -- JSON object, reason -> count (open, oversize, overCap, malformed)
    found_nothing    BOOLEAN                          -- the scan ran and proposed nothing
);
CREATE INDEX job_run_job ON engine.job_run (job_id, started_at DESC);
CREATE INDEX job_run_scan ON engine.job_run (repo, scan_issue);
