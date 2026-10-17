-- Engine-issued bearer tokens (docs/proposals/engine-authentication.md, option A, part 1 of 3; engine/README.md,
-- "Tokens"): a token is bound to a trusted login and proves it where the X-Frakworks-Login header only claims it.
-- The engine stores the token's SHA-256 (hex) and its first 8 characters after the `frk_` prefix, never the token.
CREATE TABLE engine.engine_token (
    id           VARCHAR(36) PRIMARY KEY,
    login        VARCHAR(39) NOT NULL,
    name         TEXT NOT NULL,
    prefix       CHAR(8) NOT NULL,
    hash         CHAR(64) NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(39),
    expires_at   TIMESTAMP WITH TIME ZONE,
    last_used_at TIMESTAMP WITH TIME ZONE,
    revoked_at   TIMESTAMP WITH TIME ZONE
);
CREATE UNIQUE INDEX engine_token_hash ON engine.engine_token (hash);
CREATE INDEX engine_token_login ON engine.engine_token (login);
