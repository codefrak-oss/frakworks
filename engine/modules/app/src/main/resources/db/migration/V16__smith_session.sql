-- A Smith's session as it runs (engine/README.md, "Spy: a live run
-- summarized"): every stdout line of its sandbox (Claude's stream-json
-- events), written while the run is live by SmithSessions, which
-- SmithTelemetry's follow feeds. One `smith_session` row per run, one
-- `smith_session_line` row per line in the order read; `ended_at` is set when
-- the run's telemetry ends (its sandbox exited, or `collect result`). A line
-- over 64 KiB is stored cut, with a marker.
CREATE TABLE engine.smith_session (
    run_id      VARCHAR(64) PRIMARY KEY,
    repo        VARCHAR(255) NOT NULL,
    issue       BIGINT NOT NULL,
    mode        VARCHAR(20) NOT NULL,
    model       VARCHAR(100) NOT NULL,
    started_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at    TIMESTAMP WITH TIME ZONE
);
CREATE INDEX smith_session_ticket ON engine.smith_session (repo, issue);

CREATE TABLE engine.smith_session_line (
    run_id  VARCHAR(64) NOT NULL,
    seq     BIGINT NOT NULL,
    read_at TIMESTAMP WITH TIME ZONE NOT NULL,
    line    TEXT NOT NULL,
    PRIMARY KEY (run_id, seq)
);
