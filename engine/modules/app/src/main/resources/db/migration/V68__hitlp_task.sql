-- HITLP tasks (docs/proposals/hitlp-support.md, option A, part 1 of 3; engine/README.md, "The hitlp server"): every
-- human.ask and human.approve call the hitlp MCP server took, its handle and, once terminal, its decision record.
-- request and decision are JSON kept as TEXT, as engine.job.config and deferred_issue.snapshot keep theirs (the test
-- build runs plain H2, which has no jsonb). One row per (client, idempotency_key): R4, the same key names the same task.
CREATE TABLE engine.hitlp_task (
    id                 VARCHAR(64)              PRIMARY KEY,   -- a UUID the engine minted; the Task id and the record's requestId
    client             VARCHAR(255)             NOT NULL,      -- smith:<repo>#<issue>, job:<jobId> or login:<login>
    idempotency_key    VARCHAR(256)             NOT NULL,
    primitive          VARCHAR(16)              NOT NULL CHECK (primitive IN ('ask', 'approve')),
    request            TEXT                     NOT NULL,      -- the tools/call arguments as given, JSON
    request_digest     VARCHAR(80)              NOT NULL,      -- sha256:<hex> of the key-sorted arguments, for R4
    status             VARCHAR(16)              NOT NULL CHECK (status IN ('working', 'input_required', 'completed', 'failed', 'cancelled')),
    ttl                TIMESTAMP WITH TIME ZONE NOT NULL,      -- the effective deadline (part 1: the request's, as given)
    default_on_timeout VARCHAR(16)              NOT NULL CHECK (default_on_timeout IN ('reject', 'escalate', 'cancel', 'fail')),
    assignee           VARCHAR(255),                           -- requester.onBehalfOf when a trusted login; null: everyone's
    escalated          BOOLEAN                  NOT NULL DEFAULT FALSE,
    decision           TEXT,                                   -- the decision record (spec §6), JSON, once terminal
    repo               VARCHAR(255),                           -- a Smith client's ticket, for its GET /needs row
    issue              BIGINT,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_at         TIMESTAMP WITH TIME ZONE,
    CONSTRAINT hitlp_task_client_key UNIQUE (client, idempotency_key)
);
CREATE INDEX hitlp_task_status ON engine.hitlp_task (status);
