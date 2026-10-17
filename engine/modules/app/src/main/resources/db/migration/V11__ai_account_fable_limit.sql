-- The account's Fable limit (engine/README.md, "The usage meter"): Anthropic's
-- separate cap on claude-fable-* models, which no response header reports.
-- `collect result` sets `reached` when a run's result or log names it, with
-- the account's 7-day reset at that moment; a later reading past that reset
-- sets it back to `unknown`. The picker skips a `reached` account for Fable.
ALTER TABLE engine.ai_account_usage ADD COLUMN fable_limit VARCHAR(20) DEFAULT 'unknown' NOT NULL;
ALTER TABLE engine.ai_account_usage ADD CONSTRAINT ai_account_usage_fable_limit CHECK (fable_limit IN ('unknown', 'reached'));
ALTER TABLE engine.ai_account_usage ADD COLUMN fable_limit_since TIMESTAMP WITH TIME ZONE;
ALTER TABLE engine.ai_account_usage ADD COLUMN fable_limit_reset TIMESTAMP WITH TIME ZONE;
