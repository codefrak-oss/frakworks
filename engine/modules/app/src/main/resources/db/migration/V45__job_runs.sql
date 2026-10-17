-- A Smith run with no issue (mode job, #1213: a callable-smith run on a prompt) is stamped with its job id
-- and a null ticket: the session and stats rows allow a null issue, and a friction note from such a sandbox
-- carries the job id.
ALTER TABLE engine.smith_session ALTER COLUMN issue DROP NOT NULL;
ALTER TABLE engine.smith_run_stats ALTER COLUMN issue DROP NOT NULL;
ALTER TABLE engine.friction_note ADD COLUMN job_id VARCHAR(64);
