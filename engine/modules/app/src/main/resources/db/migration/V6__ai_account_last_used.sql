-- When the picker last handed this account to a Smith (engine/README.md,
-- "AI accounts": least-recently-used among the provider's enabled accounts
-- with a credential). NULL: never picked, so it goes first.
ALTER TABLE engine.ai_account ADD COLUMN last_used_at TIMESTAMP WITH TIME ZONE;
