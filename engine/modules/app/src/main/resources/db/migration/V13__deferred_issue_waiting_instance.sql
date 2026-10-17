-- A split feature parked at its "child closed" event (engine/README.md, "Split
-- features") waits on its filed child through the same row a deferred approval
-- uses; this names the parent's running flow instance, which the scan wakes
-- with the child's close instead of starting a flow. Null for an approval.
ALTER TABLE engine.deferred_issue ADD COLUMN waiting_instance VARCHAR(64);
