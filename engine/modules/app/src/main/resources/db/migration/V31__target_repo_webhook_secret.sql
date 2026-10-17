-- A target repo's own webhook secret (engine/README.md, "When it scans"): what GitHub signs that
-- repo's deliveries to POST /hooks/github with, sealed with ENGINE_SECRETS_KEY the way its token
-- is (V27), set with PUT /repos/{owner}/{name}/webhook-secret or the desktop's Config > Repos.
-- Null: the repo's deliveries are refused (401) and it runs on the hourly scan. This is the only
-- home of the secret: the engine.webhook.secret property (WEBHOOK_SECRET in .env) is gone. No row
-- is filled here: the old value was the deployment's alone.
ALTER TABLE engine.target_repo ADD COLUMN webhook_secret        BYTEA;                       -- AES-GCM: nonce || ciphertext || tag
ALTER TABLE engine.target_repo ADD COLUMN webhook_secret_key_id VARCHAR(40);                 -- which ENGINE_SECRETS_KEY sealed it
ALTER TABLE engine.target_repo ADD COLUMN webhook_secret_set_at TIMESTAMP WITH TIME ZONE;
