-- A target repo's own GitHub token (engine/README.md, "Each repo's token"): a
-- fine-grained PAT reaching that one repo, sealed with ENGINE_SECRETS_KEY the
-- way engine.ai_account.credential is (V5), the repo's full name as associated
-- data. Null: the repo runs on the deployment's FLEET_TOKEN, as every repo did.
ALTER TABLE engine.target_repo ADD COLUMN token        BYTEA;                       -- AES-GCM: nonce || ciphertext || tag
ALTER TABLE engine.target_repo ADD COLUMN key_id       VARCHAR(40);                 -- which ENGINE_SECRETS_KEY sealed it
ALTER TABLE engine.target_repo ADD COLUMN token_set_at TIMESTAMP WITH TIME ZONE;
