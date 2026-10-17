-- An ask is a work item before refinement (kind `ask`, engine/docs/work-items.md, "Asks as work items").
ALTER TABLE engine.work_item DROP CONSTRAINT work_item_kind_check;
ALTER TABLE engine.work_item ADD CONSTRAINT work_item_kind_check CHECK (kind IN ('feature', 'bug', 'chore', 'ask'));
