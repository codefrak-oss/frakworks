-- A target repo's Smith token (engine/README.md, "Each repo's token"): a
-- fine-grained PAT with Contents and Pull requests read/write and no Issues
-- permission, the GH_TOKEN a Smith sandbox gets instead of the repo token.
-- Sealed with ENGINE_SECRETS_KEY like V27's token, the repo's full name and
-- "#smith" as associated data. Null: no Smith runs on the repo.
ALTER TABLE engine.target_repo ADD COLUMN smith_token        BYTEA;                       -- AES-GCM: nonce || ciphertext || tag
ALTER TABLE engine.target_repo ADD COLUMN smith_token_key_id VARCHAR(40);                 -- which ENGINE_SECRETS_KEY sealed it
ALTER TABLE engine.target_repo ADD COLUMN smith_token_set_at TIMESTAMP WITH TIME ZONE;
