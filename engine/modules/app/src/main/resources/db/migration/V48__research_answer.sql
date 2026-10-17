-- A research ticket's answers, one row a round (engine/README.md, "Research"):
-- answer.md (the record) and answer.html (the page the desktop's Answer section
-- draws through GET /ticket/{owner}/{repo}/{n}/pitch). Kept by the engine, never
-- committed unless the asker says save; kept after the ticket closes, so the
-- page outlives the flow's history. process_instance is the root research flow.
CREATE TABLE engine.research_answer (
    full_name         VARCHAR(200)  NOT NULL,
    issue             BIGINT        NOT NULL,
    round             INT           NOT NULL,
    process_instance  VARCHAR(64)   NOT NULL,
    summary           TEXT          NOT NULL,
    md                BYTEA         NOT NULL,
    html              BYTEA         NOT NULL,
    answered_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (full_name, issue, round)
);
