-- A ticket an intake filed that depends on an earlier intake still refining
-- when it was filed (engine/README.md, "Blocked by", "An earlier intake").
-- The ticket is blocked by the earlier intake's issue for now; when that
-- intake files its ticket, the engine re-points the relation at it and the
-- row goes.
CREATE TABLE engine.intake_dependency (
    full_name     VARCHAR(200) NOT NULL,   -- the repo of the ticket and the intake issue
    ticket        BIGINT       NOT NULL,   -- the dependent ticket
    intake        VARCHAR(64)  NOT NULL,   -- the earlier intake's process instance id
    intake_issue  BIGINT       NOT NULL,   -- its intake issue, the ticket's blocker for now
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (full_name, ticket, intake)
);
CREATE INDEX intake_dependency_intake ON engine.intake_dependency (intake);
