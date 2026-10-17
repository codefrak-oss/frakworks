-- The doctor run's Smith run (engine/README.md, "Doctor"): the `smith-run`
-- process instance the doctor's `health check` started, whose id names the
-- run's log, issue-<issue>-doctor-<smith_run_id>.log. Null until `apply the
-- remedy` records it; GET /doctor/history reads it from the engine's history
-- for a run still going.
ALTER TABLE engine.doctor_run ADD COLUMN smith_run_id VARCHAR(64);
