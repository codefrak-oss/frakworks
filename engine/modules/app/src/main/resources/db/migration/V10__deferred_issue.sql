-- Each approved ticket waiting on a GitHub "blocked by" issue that is still
-- open (engine/README.md, "Blocked by"). The relation itself stays on GitHub;
-- this only says which tickets wait, and on what approval. An approval here is
-- not in engine.handled_issue: it has not been acted on yet.
CREATE TABLE engine.deferred_issue (
    full_name     VARCHAR(200) NOT NULL,
    issue         BIGINT       NOT NULL,
    label_event   BIGINT       NOT NULL,  -- the approving labeled event; 0 for an intake carry-over
    authorized_by VARCHAR(100) NOT NULL,
    flow          VARCHAR(40)  NOT NULL,
    feature_mode  VARCHAR(20),
    snapshot      TEXT         NOT NULL,  -- JSON: the approved number, title, body, author
    tier          INT,                    -- an intake carry-over's refiner tier
    detail        VARCHAR(400),           -- an intake carry-over's "authorized by @x via intake #n"
    dry_run       BOOLEAN      NOT NULL DEFAULT FALSE,
    blockers      TEXT         NOT NULL,  -- JSON: the blockers as last read
    stalled       TEXT         NOT NULL,  -- JSON: blockers already reported closed without a merge
    first_seen    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_checked  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (full_name, issue)
);
