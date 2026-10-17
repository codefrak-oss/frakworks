-- The repo an intake's ticket is in (engine/README.md, "Intake", "Filing in another repo"): null when it is the
-- intake's own repo (`full_name`), as every intake before this filed. Set when a refiner named another target repo.
ALTER TABLE engine.intake_ticket ADD COLUMN ticket_repo VARCHAR(200);
CREATE INDEX intake_ticket_ticket_repo ON engine.intake_ticket (ticket_repo, ticket);
