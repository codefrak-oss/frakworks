-- The Smith's role on each collected run (engine/README.md, "Smith runs"):
-- the `mode` the flow's call activity passed (design, reproduce, work,
-- security, review, accept, verify, replan), so the issues board can estimate
-- a stage from past runs of the same role. Written by `collect result` from
-- now on; older rows take it from `smith_session` (same run id) where that
-- row exists and stay null otherwise.
ALTER TABLE engine.smith_run_stats ADD COLUMN mode VARCHAR(20);

UPDATE engine.smith_run_stats SET mode = (
    SELECT s.mode FROM engine.smith_session s WHERE s.run_id = engine.smith_run_stats.run_id
) WHERE mode IS NULL;
