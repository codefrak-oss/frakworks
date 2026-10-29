-- An Anthropic account's API key and Admin API key (#100142; engine/docs/ai-accounts.md, "API keys and credit"), sealed
-- like `credential` (AES-GCM under ENGINE_SECRETS_KEY, the account name and the key's kind as associated data), each
-- with the id of the key it was sealed under. The billing-cycle date anchors the monthly cycle of the plan's API credit
-- grant (cents); the engine sums the Admin API's cost_report from the cycle's start into credit_used_cents (cents, as
-- the report's decimal strings add up), with when it last did and, when the last refresh failed, why.
ALTER TABLE engine.ai_account ADD COLUMN api_key BYTEA;
ALTER TABLE engine.ai_account ADD COLUMN api_key_id VARCHAR(64);
ALTER TABLE engine.ai_account ADD COLUMN admin_key BYTEA;
ALTER TABLE engine.ai_account ADD COLUMN admin_key_id VARCHAR(64);
ALTER TABLE engine.ai_account ADD COLUMN billing_cycle_date DATE;
ALTER TABLE engine.ai_account ADD COLUMN credit_grant_cents BIGINT;
ALTER TABLE engine.ai_account ADD COLUMN credit_used_cents NUMERIC(20, 6);
ALTER TABLE engine.ai_account ADD COLUMN credit_refreshed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE engine.ai_account ADD COLUMN credit_error TEXT;
