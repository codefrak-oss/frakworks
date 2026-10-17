-- Standing approvals for hitlp (docs/proposals/hitlp-standing-approvals.md §1; engine/README.md, "The hitlp server",
-- "Standing approvals"): one row per rule a trusted login made. A rule only ever approves a human.approve; it is never
-- deleted (revoked_at ends it), for the audit trail. payload and window are JSON kept as TEXT, as hitlp_task keeps its
-- request (the test build runs plain H2, which has no jsonb). expires_at and max_uses are mandatory: no rule is open-ended.
CREATE TABLE engine.hitlp_rule (
    id          VARCHAR(64)              PRIMARY KEY,   -- a UUID the engine minted; the decision record's rule
    label       VARCHAR(255)             NOT NULL,      -- the user's words
    client      VARCHAR(255)             NOT NULL,      -- glob on the authenticated client id
    principal   VARCHAR(255),                           -- glob on requester.principal; null: any (needs a non-wildcard client)
    action      VARCHAR(255)             NOT NULL,      -- exact or glob on the request's action
    payload     TEXT                     NOT NULL,      -- JSON list of {path, op, value}; op equals, in, glob or range
    time_window TEXT,                                   -- JSON {days, start, end, zone}; null: any time
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    max_uses    INTEGER                  NOT NULL CHECK (max_uses > 0),
    uses        INTEGER                  NOT NULL DEFAULT 0,
    notify      VARCHAR(16)              NOT NULL DEFAULT 'none' CHECK (notify IN ('none', 'on-fire')),
    created_by  VARCHAR(255)             NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at  TIMESTAMP WITH TIME ZONE,
    from_task   VARCHAR(64),                            -- the hitlp_task the rule was made from, if any
    CONSTRAINT hitlp_rule_uses CHECK (uses >= 0 AND uses <= max_uses)
);
CREATE INDEX hitlp_rule_live ON engine.hitlp_rule (revoked_at, expires_at);

-- The engine tokens that may write rules (POST /hitlp/rules, revoke; §5): the user's own sign-in token, never a broker's
-- or First mate's, though every one of them proves the same trusted login. No API grants the first one, so no token can
-- grant itself: the deployment inserts the desktop's token id once (engine/README.md, "Standing approvals"); a writer
-- may then grant or withdraw others (POST/DELETE /hitlp/rule-writers/{tokenId}).
CREATE TABLE engine.hitlp_rule_writer (
    token_id   VARCHAR(36)              PRIMARY KEY,    -- engine.engine_token.id
    granted_by VARCHAR(255)             NOT NULL,       -- the writer token that granted it, or 'deployment'
    granted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
