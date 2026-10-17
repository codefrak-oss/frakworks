-- Each question a refining Smith asked on an intake, and the answer the engine
-- took for it (engine/README.md, "Intake", the ask outcome). An intake may ask
-- more than once; a row with no answered_at is waiting on the user.
CREATE TABLE engine.intake_question (
    id           BIGSERIAL    PRIMARY KEY,
    intake       VARCHAR(64)  NOT NULL,   -- the intake's process instance id
    full_name    VARCHAR(200) NOT NULL,   -- the intake repo
    issue        BIGINT       NOT NULL,   -- the intake issue
    question     TEXT         NOT NULL,   -- as the Smith wrote it: prose, then its ask block
    asked_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    answer       TEXT,
    answered_by  VARCHAR(100),            -- GitHub login; may authorize intakes on full_name
    answered_at  TIMESTAMP WITH TIME ZONE
);
CREATE INDEX intake_question_intake ON engine.intake_question (intake, asked_at);
