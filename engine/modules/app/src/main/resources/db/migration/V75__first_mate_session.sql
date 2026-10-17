-- First-mate sessions (docs/proposals/iphone-first-mate.md; engine/README.md, "First-mate sessions"): a phone's chat
-- with a first mate on one target repo, started with a token minted for the phone (engine_token.purpose 'first-mate').
-- Message bodies are JSON kept as TEXT (the test build runs plain H2, which has no jsonb).
ALTER TABLE engine.engine_token ADD COLUMN purpose VARCHAR(32);

CREATE TABLE engine.first_mate_session (
    id         VARCHAR(36) PRIMARY KEY,
    login      VARCHAR(39) NOT NULL,
    token_id   VARCHAR(36) NOT NULL,
    repo       TEXT NOT NULL,
    state      VARCHAR(16) NOT NULL,               -- open | ended
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at   TIMESTAMP WITH TIME ZONE
);
CREATE INDEX first_mate_session_token ON engine.first_mate_session (token_id);

CREATE TABLE engine.first_mate_message (
    session_id VARCHAR(36) NOT NULL REFERENCES engine.first_mate_session (id) ON DELETE CASCADE,
    seq        INT NOT NULL,
    role       VARCHAR(16) NOT NULL,               -- user | assistant | system
    body       TEXT NOT NULL,                      -- JSON
    at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, seq)
);
