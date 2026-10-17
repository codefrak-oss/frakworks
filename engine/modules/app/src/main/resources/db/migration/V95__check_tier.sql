-- The tier every post-deployment check run (Smith mode `check`) runs at (#100009; engine/docs/smith-runs.md,
-- "The check mode"), beside security.tier (V42): TierTable.checkTier(), GET/PUT /check/tier. Seeded 4: a cheap
-- read-and-answer run.
INSERT INTO engine.engine_setting (name, setting) VALUES ('check.tier', '4');
