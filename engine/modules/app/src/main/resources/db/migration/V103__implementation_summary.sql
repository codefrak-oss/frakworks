-- A ticket's implementation summary (#100141; engine/README.md, "Implementation summary"): the 10-50 words its latest
-- accepted `done` work result gave on what the change did. Written on every such result, latest wins; served on the
-- ticket detail only once the ticket is closed with a merged PR. Tickets done before this have no row and show none.
CREATE TABLE engine.implementation_summary (
    full_name   TEXT NOT NULL,
    issue       BIGINT NOT NULL,
    summary     TEXT NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (full_name, issue)
);
