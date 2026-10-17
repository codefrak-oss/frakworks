-- A ticket's labels become typed columns (#100032, engine/docs/work-items.md, "Tickets"): kind and mode were already
-- columns, so V94's comma-separated labels only add a feature's mode (carried where the column is null) and the four
-- labels the code still reads, each now a flag. No other label is read by code; the rest are dropped with the column.
UPDATE engine.work_item SET mode = 'auto'
    WHERE mode IS NULL AND kind = 'feature' AND ',' || labels || ',' LIKE '%,feature:auto,%';
UPDATE engine.work_item SET mode = 'confirm'
    WHERE mode IS NULL AND kind = 'feature' AND ',' || labels || ',' LIKE '%,feature:confirm,%';
-- A refactoring ticket (REFACTOR_LABEL).
ALTER TABLE engine.work_item ADD COLUMN refactor BOOLEAN NOT NULL DEFAULT FALSE;
-- Authorized to start (IssueScanner.AUTHORIZED).
ALTER TABLE engine.work_item ADD COLUMN authorized BOOLEAN NOT NULL DEFAULT FALSE;
-- A bug waiting on a reproduction (DoctorBugs.NEEDS_REPRO).
ALTER TABLE engine.work_item ADD COLUMN needs_repro BOOLEAN NOT NULL DEFAULT FALSE;
-- A doctor bug for a human's review (DoctorBugs.NEEDS_REVIEW).
ALTER TABLE engine.work_item ADD COLUMN needs_review BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE engine.work_item SET refactor = TRUE WHERE ',' || labels || ',' LIKE '%,refactor,%';
UPDATE engine.work_item SET authorized = TRUE WHERE ',' || labels || ',' LIKE '%,authorized,%';
UPDATE engine.work_item SET needs_repro = TRUE WHERE ',' || labels || ',' LIKE '%,needs-repro,%';
UPDATE engine.work_item SET needs_review = TRUE WHERE ',' || labels || ',' LIKE '%,needs-review,%';
ALTER TABLE engine.work_item DROP COLUMN labels;
