-- The account's token lives here, encrypted with the engine's ENGINE_SECRETS_KEY
-- (engine/README.md, "AI accounts"): a backup or psql sees only ciphertext.
-- secret_ref (a broker key, V2) goes: the credential is the row's, not a
-- pointer. It can return as an alternative source if a deployment needs one.
ALTER TABLE engine.ai_account DROP COLUMN secret_ref;
ALTER TABLE engine.ai_account ADD COLUMN enabled    BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE engine.ai_account ADD COLUMN credential BYTEA;                          -- AES-GCM: nonce || ciphertext || tag; BYTEA: Postgres has no BINARY VARYING, H2 takes both
ALTER TABLE engine.ai_account ADD COLUMN key_id     VARCHAR(40);                    -- which ENGINE_SECRETS_KEY sealed it
ALTER TABLE engine.ai_account ADD COLUMN credential_set_at TIMESTAMP WITH TIME ZONE;
