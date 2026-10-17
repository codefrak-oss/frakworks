-- The HITLP server the engine talks to (#2500, part 1 of #2498; engine/README.md, "The HITLP requester"): every value
-- about it lives here, set through PUT /settings/hitlp, never in a property or an environment variable (the old
-- engine.hitlp.url / engine.hitlp.token homes are gone). One row: the requester's MCP endpoint, its bearer token sealed
-- under the engine's key (AAD `engine.hitlp.requester`) with the key's id and when it was set, and the surface base URL
-- (ending /surface/v1) part 2 of #2498 reads. Nothing is seeded (as V72 for the public URL): a deployment carries its
-- values over by the API.
CREATE TABLE engine.hitlp_server (
    singleton               BOOLEAN DEFAULT TRUE PRIMARY KEY,
    mcp_url                 TEXT,
    requester_token         BYTEA,
    requester_key_id        TEXT,
    requester_token_set_at  TIMESTAMP WITH TIME ZONE,
    surface_url             TEXT,
    CONSTRAINT hitlp_server_singleton CHECK (singleton)
);

-- One surface credential per trusted login (part 2 of #2498 sends it as the login's bearer token to the surface),
-- sealed under the engine's key with AAD `hitlp-surface:<login>`; set through PUT /settings/hitlp/surface-credential
-- by the login itself, after the engine tried it once against the surface.
CREATE TABLE engine.hitlp_surface_credential (
    login       TEXT PRIMARY KEY,
    credential  BYTEA NOT NULL,
    key_id      TEXT NOT NULL,
    set_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Whether frakworks facilitates the repo's HITLP requests as a surface (part 2 of #2498 gates on it; until then it
-- gates nothing). Off for every existing repo; set through PATCH /repos/{owner}/{name} { hitlpSurface }.
ALTER TABLE engine.target_repo ADD COLUMN hitlp_surface BOOLEAN NOT NULL DEFAULT FALSE;
