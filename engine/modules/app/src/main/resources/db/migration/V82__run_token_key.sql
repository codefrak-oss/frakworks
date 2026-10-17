-- The engine's run identity token keys (engine/README.md, "Run identity tokens"; docs/run-identity-token.md): one
-- P-256 key pair per row, the newest unretired row signing. `public_jwk` is the public half as a JWK (published at
-- GET /.well-known/jwks.json); `private_key` the PKCS#8 private half sealed with ENGINE_SECRETS_KEY (the kid is the
-- associated data). A rotated key keeps its row with `retired_at` set, still published, until its last token expires.
-- `engine.run_token_revoked` names runs whose tokens introspection answers inactive before their session ends.
CREATE TABLE engine.run_token_key (
    kid         VARCHAR(64) PRIMARY KEY,
    public_jwk  VARCHAR(1000) NOT NULL,
    private_key BYTEA NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    retired_at  TIMESTAMP WITH TIME ZONE
);

CREATE TABLE engine.run_token_revoked (
    run_id      VARCHAR(64) PRIMARY KEY,
    revoked_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
