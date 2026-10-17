-- The deployment patterns (#100115; engine/README.md, "Deployment patterns"): the extended regexes the engine checks
-- every Smith push's added lines against, and the exact benign lines that pass, each sealed under the engine's key
-- (AAD `engine.deployment.patterns`, `engine.deployment.allow`) with the key's id and when it was set. Set through
-- PUT /settings/deployment-patterns only; nothing is seeded: the patterns are the deployment's, never the repo's.
CREATE TABLE engine.deployment_patterns (
    singleton          BOOLEAN DEFAULT TRUE PRIMARY KEY,
    patterns           BYTEA,
    patterns_key_id    TEXT,
    patterns_set_at    TIMESTAMP WITH TIME ZONE,
    allow              BYTEA,
    allow_key_id       TEXT,
    allow_set_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT deployment_patterns_singleton CHECK (singleton)
);
