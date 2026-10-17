-- A rejection read from a run's result text (engine/README.md, "The usage
-- meter"): "You've hit your org's monthly spend limit", "your session limit
-- resets 3:10pm (UTC)". `collect result` sets it until the reset the text
-- gives, else the meter's; the next meter reading does not overwrite it. The
-- picker skips the account while `limit_rejected_reset` is ahead; a reading
-- past it clears it.
ALTER TABLE engine.ai_account_usage ADD COLUMN limit_rejected_since TIMESTAMP WITH TIME ZONE;
ALTER TABLE engine.ai_account_usage ADD COLUMN limit_rejected_reset TIMESTAMP WITH TIME ZONE;
