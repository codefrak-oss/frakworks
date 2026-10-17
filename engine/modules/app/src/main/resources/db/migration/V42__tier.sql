-- The tier map (engine/README.md, "Smith runs", "Its own tables"): what each of the ten tiers
-- runs, a model (TierTable.CLAUDE_MODELS' short name) and an effort, edited on the API
-- (PUT /tiers/{tier}) and in the desktop's Config > frakworks > Tiers. It was bots/tiers.toml's
-- [claude] table until 2026-10-05 (#1099); the rows below are that table as main had it, so
-- every open ticket resolves to the same model and effort before and after the deploy. The file's
-- [codex] column was not carried over: nothing resolved it. Always ten rows: no endpoint adds or
-- removes one, and the engine refuses to start without all ten (TierTable).
CREATE TABLE engine.tier (
    tier        SMALLINT     PRIMARY KEY CHECK (tier BETWEEN 1 AND 10),
    model       VARCHAR(40)  NOT NULL,   -- a short name: haiku, sonnet, opus, fable
    effort      VARCHAR(20)  NOT NULL,   -- one the model takes (TierTable.EFFORTS)
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO engine.tier (tier, model, effort) VALUES
    (1, 'haiku', 'low'),
    (2, 'sonnet', 'low'),
    (3, 'sonnet', 'medium'),
    (4, 'opus', 'low'),
    (5, 'opus', 'medium'),
    (6, 'opus', 'medium'),
    (7, 'opus', 'medium'),
    (8, 'fable', 'low'),
    (9, 'fable', 'medium'),
    (10, 'fable', 'high');

-- The one tier every security review runs at, whatever the reviewed ticket's own tier
-- (bots/tiers.toml's security_tier until 2026-10-05; GET/PUT /security/tier).
INSERT INTO engine.engine_setting (name, setting) VALUES ('security.tier', '5');
