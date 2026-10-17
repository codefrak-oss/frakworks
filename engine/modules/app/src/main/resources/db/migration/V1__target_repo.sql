-- The engine's own schema (engine/README.md, "Its own tables"). Created here,
-- unquoted, not by spring.flyway.schemas: Flyway quotes the name, and H2
-- upper-cases the unquoted `engine.` in every query, so they never meet.
CREATE SCHEMA IF NOT EXISTS engine;

-- The repos the engine works on.
CREATE TABLE engine.target_repo (
    full_name  VARCHAR(200) PRIMARY KEY,          -- owner/name
    added_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
