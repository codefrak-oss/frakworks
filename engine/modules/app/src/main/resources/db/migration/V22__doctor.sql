-- The doctor (engine/README.md, "Doctor"). doctor_position: every running
-- ticket flow's position as the last tick saw it, and since when it has been
-- there; a row goes when its flow ends. doctor_run: one row per doctor run,
-- written when it starts and finished by its `apply the remedy` step.
CREATE TABLE engine.doctor_position (
    root_instance_id  VARCHAR(64)   PRIMARY KEY,   -- the ticket's root flow
    repo              VARCHAR(200)  NOT NULL,
    issue             BIGINT        NOT NULL,
    position          TEXT          NOT NULL,      -- sorted <process>:<activity>@<wait> leaves and incident:<id>, comma-separated (TicketPosition.key)
    since             TIMESTAMP WITH TIME ZONE NOT NULL,   -- when the flow came to this position
    observed_at       TIMESTAMP WITH TIME ZONE NOT NULL    -- the last tick that saw it
);

CREATE TABLE engine.doctor_run (
    id                VARCHAR(64)   PRIMARY KEY,   -- the doctor process instance
    repo              VARCHAR(200)  NOT NULL,
    issue             BIGINT        NOT NULL,
    root_instance_id  VARCHAR(64)   NOT NULL,
    position          TEXT          NOT NULL,      -- the position it was stuck at
    stuck_since       TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at          TIMESTAMP WITH TIME ZONE,
    diagnosis         TEXT,                        -- the Smith's, null until it ends or when it left none
    remedy            VARCHAR(50),                 -- healthy, unfixable, retry, collect-exit or run-job
    target            VARCHAR(200),                -- the incident or job the remedy names
    outcome           VARCHAR(50)   NOT NULL,      -- running, applied, none or failed
    detail            TEXT                         -- why it failed
);
CREATE INDEX doctor_run_ticket ON engine.doctor_run (repo, issue);
CREATE INDEX doctor_run_root ON engine.doctor_run (root_instance_id);
