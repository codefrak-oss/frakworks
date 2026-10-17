-- Friction notes (engine/README.md, "Its own tables"): one row per thing a
-- Smith found buggy, badly designed, confusing or awkward but not worth a
-- ticket, from its note_friction tool (as-you-go) or the retro turn after its
-- run (retro). The engine stamps where it came from; the Smith gives kind,
-- summary, evidence and severity. Nothing reads it but read_friction_notes.
CREATE TABLE engine.friction_note (
    id                   VARCHAR(64)   PRIMARY KEY,   -- a UUID the engine mints
    repo                 VARCHAR(200),                -- the run's repo
    issue                BIGINT,                      -- the run's ticket
    run_id               VARCHAR(64),                 -- the smith-run process instance
    process_instance_id  VARCHAR(64),                 -- its root flow; null when history has none
    noted_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    source               VARCHAR(20)   NOT NULL,      -- as-you-go or retro
    kind                 VARCHAR(20)   NOT NULL,      -- bug, design, docs, tooling or other
    summary              TEXT          NOT NULL,
    evidence             TEXT,                        -- the exact error, path, command or workaround
    severity             VARCHAR(10)                  -- low, medium or high; null when not given
);
CREATE INDEX friction_note_ticket ON engine.friction_note (repo, issue);
CREATE INDEX friction_note_noted_at ON engine.friction_note (noted_at);
