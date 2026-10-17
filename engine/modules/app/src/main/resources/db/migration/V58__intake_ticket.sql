-- The link from an intake to its issue and the ticket it filed (engine/README.md, "Intake"): written when the
-- intake issue is created and when the intake files its ticket. Application data, not Operaton variables:
-- `IntakeTicketBackfill` copies the links of intakes started before this table from Operaton's history once.
CREATE TABLE engine.intake_ticket (
    intake      VARCHAR(64)  PRIMARY KEY,  -- the intake's process instance id
    full_name   VARCHAR(200) NOT NULL,     -- the intake repo
    issue       BIGINT       NOT NULL,     -- the intake issue
    issue_url   VARCHAR(500) NOT NULL,     -- the intake issue's url
    ticket      BIGINT,                    -- the ticket it filed; null until it files
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    filed_at    TIMESTAMP WITH TIME ZONE
);
CREATE INDEX intake_ticket_issue ON engine.intake_ticket (full_name, issue);
CREATE INDEX intake_ticket_ticket ON engine.intake_ticket (full_name, ticket);
CREATE INDEX intake_ticket_url ON engine.intake_ticket (issue_url);
