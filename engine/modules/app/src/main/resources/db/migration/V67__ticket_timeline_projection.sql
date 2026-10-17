-- The ticket timeline's projection (#1966; engine/README.md, "Ticket timeline"): what GET /ticket/{owner}/{repo}/{n}/timeline
-- and read_ticket_runs read instead of Operaton's history. Fed by the history handler (FlowEventHandler -> TimelineProjection,
-- inside the command's transaction) and the Smith delegates; filled once per ticket from history by TimelineBackfill.

-- One row per root flow: its ticket (repo, issue), kind (the process definition key), the approved or filed ticket's title
-- (the first seen; an amendment rewriting the ticket leaves it) and the flow's tier.
CREATE TABLE engine.ticket_flow (
    root     VARCHAR(64) PRIMARY KEY,
    repo     VARCHAR(255),
    issue    BIGINT,
    kind     VARCHAR(255) NOT NULL,
    title    VARCHAR(1000),
    tier     INTEGER,
    started  TIMESTAMP WITH TIME ZONE NOT NULL,
    ended    TIMESTAMP WITH TIME ZONE
);
CREATE INDEX ticket_flow_issue ON engine.ticket_flow (repo, issue);

-- Every process instance, roots and called processes alike, under its root. `ci_check` is the instance's last `ciCheck`,
-- copied onto a `receive-ci-check` wait as it starts.
CREATE TABLE engine.flow_instance (
    id             VARCHAR(64) PRIMARY KEY,
    root           VARCHAR(64) NOT NULL,
    super_instance VARCHAR(64),
    kind           VARCHAR(255) NOT NULL,
    started        TIMESTAMP WITH TIME ZONE NOT NULL,
    ended          TIMESTAMP WITH TIME ZONE,
    cancelled      BOOLEAN NOT NULL DEFAULT FALSE,
    ci_check       VARCHAR(255)
);
CREATE INDEX flow_instance_root ON engine.flow_instance (root);

-- Every activity instance of those instances. `seq` is Operaton's sequence counter of the activity instance, the order of
-- the activities that started in the same millisecond (the timeline reads them by started, then seq).
CREATE TABLE engine.flow_activity (
    id            VARCHAR(64) PRIMARY KEY,
    instance      VARCHAR(64) NOT NULL,
    root          VARCHAR(64) NOT NULL,
    activity_id   VARCHAR(255) NOT NULL,
    activity_type VARCHAR(255),
    seq           BIGINT NOT NULL DEFAULT 0,
    started       TIMESTAMP WITH TIME ZONE NOT NULL,
    ended         TIMESTAMP WITH TIME ZONE
);
CREATE INDEX flow_activity_root ON engine.flow_activity (root);

-- Every incident: `state` open, resolved or deleted; `root_cause` the incident it propagated up from (null for a root cause).
CREATE TABLE engine.flow_incident (
    id          VARCHAR(64) PRIMARY KEY,
    instance    VARCHAR(64) NOT NULL,
    root        VARCHAR(64) NOT NULL,
    activity_id VARCHAR(255),
    message     VARCHAR(4000),
    root_cause  VARCHAR(64),
    created     TIMESTAMP WITH TIME ZONE NOT NULL,
    ended       TIMESTAMP WITH TIME ZONE,
    state       VARCHAR(20) NOT NULL
);
CREATE INDEX flow_incident_root ON engine.flow_incident (root);

-- One row per `receive-ci-check` wait (its activity instance id), with the check it waited for and how it ended:
-- `conclusion` the check run's, `outcome` the flow's `ciOutcome` after it (`rerun` when `read check` re-ran the check).
CREATE TABLE engine.ci_wait (
    id          VARCHAR(64) PRIMARY KEY,
    instance    VARCHAR(64) NOT NULL,
    root        VARCHAR(64) NOT NULL,
    started     TIMESTAMP WITH TIME ZONE NOT NULL,
    ended       TIMESTAMP WITH TIME ZONE,
    check_name  VARCHAR(255),
    conclusion  VARCHAR(100),
    outcome     VARCHAR(40)
);
CREATE INDEX ci_wait_root ON engine.ci_wait (root);

-- A review or security run's findings as `read result` stamped them (ReviewResult.stamped), one row each, as JSON.
CREATE TABLE engine.review_finding (
    run_id    VARCHAR(64) NOT NULL,
    position  INTEGER NOT NULL,
    review_no INTEGER NOT NULL,
    finding   TEXT NOT NULL,
    PRIMARY KEY (run_id, position)
);

-- One row per ticket the projection was filled for from history, by the lease holder's sweep (`startup`) or a request (`request`).
CREATE TABLE engine.timeline_backfill (
    repo          VARCHAR(255) NOT NULL,
    issue         BIGINT NOT NULL,
    backfilled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    source        VARCHAR(20) NOT NULL,
    roots         INTEGER NOT NULL,
    PRIMARY KEY (repo, issue)
);

-- A run's row exists from `start smith` on: `ended`, `exit_code` and what `collect result` records are null until then.
ALTER TABLE engine.smith_run_stats ALTER COLUMN ended DROP NOT NULL;
ALTER TABLE engine.smith_run_stats ALTER COLUMN exit_code DROP NOT NULL;
ALTER TABLE engine.smith_run_stats ADD COLUMN root VARCHAR(64);
ALTER TABLE engine.smith_run_stats ADD COLUMN wrapper VARCHAR(64);
ALTER TABLE engine.smith_run_stats ADD COLUMN outcome VARCHAR(100);
ALTER TABLE engine.smith_run_stats ADD COLUMN question VARCHAR(4000);
ALTER TABLE engine.smith_run_stats ADD COLUMN turns BIGINT;
ALTER TABLE engine.smith_run_stats ADD COLUMN duration_ms BIGINT;
ALTER TABLE engine.smith_run_stats ADD COLUMN result VARCHAR(20);
ALTER TABLE engine.smith_run_stats ADD COLUMN review_no INTEGER;
CREATE INDEX smith_run_stats_root ON engine.smith_run_stats (root);
CREATE INDEX smith_run_stats_wrapper ON engine.smith_run_stats (wrapper);

-- The intake's refine result, parsed once from the `output` the refine run copies onto the intake: the timeline's `blocked`.
ALTER TABLE engine.intake_ticket ADD COLUMN refine_outcome VARCHAR(100);
ALTER TABLE engine.intake_ticket ADD COLUMN blocker VARCHAR(4000);
ALTER TABLE engine.intake_ticket ADD COLUMN reason TEXT;
