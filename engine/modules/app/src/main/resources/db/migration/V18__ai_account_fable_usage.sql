-- The Fable limit's meter (engine/README.md, "The usage meter"): a
-- claude-fable-* reply carries `anthropic-ratelimit-unified-7d_oi-utilization`
-- and `-7d_oi-reset`, found live 2026-09-26. Written with every reading like
-- the 5-hour and 7-day columns; a reading without the pair (any other model)
-- stores null. Separate from `fable_limit`, which the picker still gates on.
ALTER TABLE engine.ai_account_usage ADD COLUMN fable_utilization DOUBLE PRECISION;
ALTER TABLE engine.ai_account_usage ADD COLUMN fable_reset TIMESTAMP WITH TIME ZONE;
