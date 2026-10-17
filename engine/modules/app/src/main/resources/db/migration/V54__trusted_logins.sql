-- One engine-wide list of trusted GitHub logins replaces the per-kind grants
-- (docs/proposals/collapse-access-kinds.md): every distinct login that held a
-- ticket kind, on a repo or engine-wide, is carried over; `jobs` grants are
-- dropped, not carried (the list does not gate jobs). The audits keep their
-- old rows, and each carried login gets a `migration` entry.
CREATE TABLE engine.trusted_login_carry (login VARCHAR(100) NOT NULL PRIMARY KEY);
INSERT INTO engine.trusted_login_carry (login)
    SELECT MIN(a.login) FROM (
        SELECT login FROM engine.repo_access WHERE flow <> 'jobs'
        UNION ALL
        SELECT login FROM engine.engine_access WHERE flow <> 'jobs'
    ) a GROUP BY LOWER(a.login);

DROP TABLE engine.engine_access;
DROP TABLE engine.repo_access;

-- Who the engine trusts: their labels, answers, cancels, retries and First
-- mate actions count on every target repo (engine/README.md, "Starting a flow
-- from an issue").
CREATE TABLE engine.engine_access (
    login       VARCHAR(100) NOT NULL PRIMARY KEY,  -- a GitHub login
    granted_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO engine.engine_access (login) SELECT login FROM engine.trusted_login_carry;

-- A row from here on has no kind: change is grant | revoke | migration.
ALTER TABLE engine.engine_access_audit ALTER COLUMN flow DROP NOT NULL;
INSERT INTO engine.engine_access_audit (change, login) SELECT 'migration', login FROM engine.trusted_login_carry;

DROP TABLE engine.trusted_login_carry;
