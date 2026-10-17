-- The AI accounts the engine can run Smiths on (engine/README.md, "Its own
-- tables"). No credentials here: secret_ref names where the token lives.
CREATE TABLE engine.ai_account (
    name        VARCHAR(100) PRIMARY KEY,         -- e.g. anthropic-1
    provider    VARCHAR(20)  NOT NULL CHECK (provider IN ('anthropic', 'openai')),
    secret_ref  VARCHAR(200) NOT NULL,            -- secrets store key, not the secret
    added_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
