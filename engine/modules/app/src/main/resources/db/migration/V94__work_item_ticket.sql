-- Work items are the tickets (#2567's cutover, engine/docs/work-items.md, "Tickets"): every item gets the ticket number its
-- flows, branches and events use, from this sequence. It starts far above any GitHub issue number in a target repo, so an
-- item's number never names an issue, and the engine's ticket calls tell the two apart by it (WorkItemTickets).
CREATE SEQUENCE engine.work_item_ticket START WITH 100000;
UPDATE engine.work_item SET ticket = nextval('engine.work_item_ticket') WHERE ticket IS NULL;
-- Who asked for it (a login, or `engine`): the ticket's author on the issue-shaped reads.
ALTER TABLE engine.work_item ADD COLUMN author TEXT;
CREATE INDEX work_item_ticket_idx ON engine.work_item (full_name, ticket);
ALTER TABLE engine.work_item DROP CONSTRAINT work_item_kind_check;
ALTER TABLE engine.work_item ADD CONSTRAINT work_item_kind_check CHECK (kind IN ('feature', 'bug', 'chore', 'proposal', 'research', 'ask'));
-- The labels a ticket was filed with (comma-separated: its kind, a feature's mode, a refactoring ticket's `refactor`), null
-- for an item filed from an ask, whose labels are its kind and mode.
ALTER TABLE engine.work_item ADD COLUMN labels TEXT;
