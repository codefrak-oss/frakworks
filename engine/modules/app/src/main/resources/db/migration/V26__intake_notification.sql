-- One row per intake that asked to have its filed ticket pushed back into the
-- Claude Code session that submitted it (engine/README.md, "Intake", "Telling
-- the session that filed it"). Queued by the intake's `notify the session that
-- filed it` step; a host-side watcher (scripts/intake-notify-watcher.py)
-- delivers it with `claude -p` and records the result. One row per intake,
-- ever: the unique key is what stops a second delivery.
CREATE TABLE engine.intake_notification (
    id            BIGSERIAL    PRIMARY KEY,
    intake        VARCHAR(64)  NOT NULL UNIQUE,  -- the intake's process instance id
    session       VARCHAR(200) NOT NULL,         -- the Claude Code session name to message
    full_name     VARCHAR(200) NOT NULL,         -- the repo the ticket was filed in
    ticket        BIGINT       NOT NULL,         -- the filed ticket's number
    ticket_url    VARCHAR(500) NOT NULL,
    queued_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at  TIMESTAMP WITH TIME ZONE,      -- when the watcher reported; null while it waits
    delivered_by  VARCHAR(100),                  -- the watcher's name (its host, by default)
    result        TEXT                           -- what `claude -p` printed (its msg_id line), or the failure
);
CREATE INDEX intake_notification_pending ON engine.intake_notification (delivered_at, queued_at);
