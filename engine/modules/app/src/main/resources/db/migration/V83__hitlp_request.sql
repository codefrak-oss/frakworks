-- The engine as a HITLP requester (#2447, part 1 of the plan in #2443; spec/hitlp.md rule R3): one row per request the
-- engine filed on the HITLP server, the Task handle it holds for a flow wait, so a restarted engine resumes polling from
-- the row and never files the same wait twice (its idempotency_key is the row's). record is the decision record once the
-- task is terminal, JSON kept as TEXT as hitlp_task keeps its request and decision (the test build runs plain H2, which
-- has no jsonb and no partial index); a row with a record is finished and never polled again.
CREATE TABLE engine.hitlp_request (
    task_id          VARCHAR(255)             PRIMARY KEY,   -- the server's Task id
    idempotency_key  VARCHAR(256)             NOT NULL,      -- the key the engine chose for the request (R4)
    process_instance VARCHAR(64)              NOT NULL,      -- the flow waiting on it
    user_task_key    VARCHAR(255)             NOT NULL,      -- the wait within the flow
    kind             VARCHAR(32)              NOT NULL,      -- HitlpAsserted.Kind: QUESTION, DECISION, ACK, ACCEPTANCE, MERGE
    poll_interval_ms INTEGER                  NOT NULL,      -- the handle's pollInterval, as last given
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_poll_at     TIMESTAMP WITH TIME ZONE NOT NULL,      -- when the tick next asks tasks/get
    record           TEXT,                                   -- the decision record (spec section 6), JSON, once terminal
    judge_call_id    VARCHAR(64)                             -- the Jev call that judged the record, when one did
);
-- The tick reads the open rows due now: WHERE record IS NULL AND next_poll_at <= now (HitlpRequests.due).
CREATE INDEX hitlp_request_next_poll ON engine.hitlp_request (next_poll_at);
CREATE INDEX hitlp_request_process_instance ON engine.hitlp_request (process_instance);
