-- Doctor runs as bug sightings (engine/README.md, "Doctor"; docs/proposals/doctor-to-bugs.md §5).
-- doctor_sighting: one row per doctor run on a ticket that was really stuck (every outcome but
-- healthy, a failed doctor run included), written in every repo: an engine row, no GitHub write.
-- doctor_signature: each distinct signature seen, the exact key sightings lump by. doctor_bug: the
-- bugs the doctor feeds; nothing writes it yet (filing comes later), so every bug_id is null.
CREATE TABLE engine.doctor_bug (
    id                BIGSERIAL     PRIMARY KEY,
    repo              VARCHAR(200)  NOT NULL,
    issue             BIGINT,                      -- null while its intake refines
    intake_id         VARCHAR(64),
    state             VARCHAR(20)   NOT NULL,      -- filing, open, needs-repro, fixed or declined
    regression_of     BIGINT        REFERENCES engine.doctor_bug (id),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at         TIMESTAMP WITH TIME ZONE
);

CREATE TABLE engine.doctor_signature (
    signature         VARCHAR(64)   PRIMARY KEY,   -- SHA-256, hex (Signature.of)
    bug_id            BIGINT        REFERENCES engine.doctor_bug (id),
    "where"           VARCHAR(20)   NOT NULL,      -- engine, target or none
    leaves            TEXT          NOT NULL,      -- the sorted leaves, without incident ids, comma-separated
    shape             TEXT,                        -- the normalised incident lines, one per line, for reading
    first_seen        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE engine.doctor_sighting (
    id                BIGSERIAL     PRIMARY KEY,
    doctor_run_id     VARCHAR(64)   NOT NULL,      -- the run that ended it (engine.doctor_run.id)
    earlier_run_id    VARCHAR(64),                 -- the applied retry that did not hold
    repo              VARCHAR(200)  NOT NULL,
    issue             BIGINT        NOT NULL,
    root_instance_id  VARCHAR(64)   NOT NULL,      -- the stuck ticket's root flow
    kind              VARCHAR(20)   NOT NULL,      -- unfixable, retried, not-held, backstop, remedy-failed or doctor-failed
    run_class         VARCHAR(40),                 -- the failed run's class in the signature (oom, rate-limited, exit:<code>), or null
    "where"           VARCHAR(20)   NOT NULL,
    summary           TEXT,
    signature         VARCHAR(64)   NOT NULL REFERENCES engine.doctor_signature (signature),
    bug_id            BIGINT        REFERENCES engine.doctor_bug (id),
    lumped_by         VARCHAR(20),                 -- exact, jev, new, none or leftover; null while nothing lumps
    jev_call_id       VARCHAR(64),
    seen_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX doctor_sighting_run ON engine.doctor_sighting (doctor_run_id);
CREATE INDEX doctor_sighting_signature ON engine.doctor_sighting (signature);
CREATE INDEX doctor_sighting_root ON engine.doctor_sighting (root_instance_id);
