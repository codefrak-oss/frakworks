-- Custom jobs (engine/README.md, "Custom jobs and the SDK"; part 2 of #1212): what to run, in which target repo,
-- and on what signal. config and signal are JSON kept as TEXT, as engine_setting and deferred_issue keep theirs,
-- so a new signal kind or config key is a parser branch, not a migration. A job goes with its target repo.
CREATE TABLE engine.job (
    id             VARCHAR(36)  PRIMARY KEY,      -- a UUID the engine minted
    name           VARCHAR(200) NOT NULL,
    kind           VARCHAR(10)  NOT NULL CHECK (kind IN ('prompt', 'bean', 'flow')),
    repo           VARCHAR(200) NOT NULL REFERENCES engine.target_repo (full_name) ON DELETE CASCADE,
    config         TEXT         NOT NULL,         -- prompt: {"prompt"}; bean: {"jar", "bean"}; flow: {"jar", "process"}
    signal         TEXT         NOT NULL,         -- {"kind": "every", "every": N} | {"kind": "execute"}
    created_by     VARCHAR(100),                  -- the X-Frakworks-Login that registered it
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_run_at    TIMESTAMP WITH TIME ZONE,      -- null unless the signal is every
    last_run_at    TIMESTAMP WITH TIME ZONE,
    last_instance  VARCHAR(64)                    -- the last job process instance started for it
);
