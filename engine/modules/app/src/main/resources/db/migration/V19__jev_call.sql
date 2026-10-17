-- Every call the engine makes to Jev (engine/README.md, "Jev accounts"),
-- failed or not, as JevCalls writes it: what was asked, what came back, what
-- the engine did with the pick, and later what the flow learned (outcome), so
-- the table is the fleet's own evaluation set for Jev. A failed or timed-out
-- call has no response and says why in error. Never the key.
CREATE TABLE engine.jev_call (
    id                VARCHAR(64)  PRIMARY KEY,
    called_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    use_case          VARCHAR(50)  NOT NULL,   -- e.g. acceptance
    process_instance  VARCHAR(64),
    repo              VARCHAR(200),
    issue             BIGINT,
    account           VARCHAR(100),            -- the jev account billed; null when none was usable
    model             VARCHAR(50)  NOT NULL,   -- the version that answered, else the one pinned
    request           TEXT         NOT NULL,   -- model, state and questions, as sent
    response          TEXT,                    -- the body as received; null on failure
    error             TEXT,                    -- why there is no answer; null on success
    latency_ms        BIGINT,
    threshold         DOUBLE PRECISION,        -- the confidence threshold in force
    action            VARCHAR(100),            -- what the engine did with the pick
    outcome           TEXT,                    -- filled later by the flow
    outcome_at        TIMESTAMP WITH TIME ZONE
);
CREATE INDEX jev_call_ticket ON engine.jev_call (repo, issue);
