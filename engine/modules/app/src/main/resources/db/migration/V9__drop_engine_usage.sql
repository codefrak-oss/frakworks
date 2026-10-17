-- The engine no longer has a token of its own (engine/README.md, "AI
-- accounts"), so the meter's `engine` row is a stale reading of nothing.
DELETE FROM engine.ai_account_usage WHERE account = 'engine';
