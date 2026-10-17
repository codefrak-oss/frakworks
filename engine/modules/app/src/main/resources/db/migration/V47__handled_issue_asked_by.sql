-- Who asked for the ticket (engine/README.md, "Intake", `by`): the trailer's `by <login>`, else the issue's
-- GitHub author, written where a flow is claimed. Rows from before this column, on an open issue, are filled
-- by the scan on its next pass; there is no backfill script.
ALTER TABLE engine.handled_issue ADD COLUMN asked_by VARCHAR(100);
