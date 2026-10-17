-- When an intake began filing its ticket on GitHub (#1802; engine/README.md, "Intake"): written in its own transaction
-- before the create call, as `ticket` is right after it, so a job retry after a rollback finds the ticket it filed
-- (`ticket`), or knows a create may have reached GitHub (`filing_at` set, `ticket` null) and looks for it by the
-- intake id in the open tickets' trailers, rather than filing a second one.
ALTER TABLE engine.intake_ticket ADD COLUMN filing_at TIMESTAMP WITH TIME ZONE;
