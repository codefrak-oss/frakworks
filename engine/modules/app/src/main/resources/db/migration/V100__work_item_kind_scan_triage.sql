-- A refactoring job's scan ticket and a ticket filed for human triage are work items too (#100053): kinds `refactor-scan`
-- and `triage`, which TicketFiler created on GitHub before.
ALTER TABLE engine.work_item DROP CONSTRAINT work_item_kind_check;
ALTER TABLE engine.work_item ADD CONSTRAINT work_item_kind_check
    CHECK (kind IN ('feature', 'bug', 'chore', 'proposal', 'research', 'ask', 'refactor-scan', 'triage'));
