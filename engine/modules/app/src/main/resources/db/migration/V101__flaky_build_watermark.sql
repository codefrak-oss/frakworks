-- The last `images` workflow run on `main` read for flaky tests, per repo (#100068): each run is read once ever.
CREATE TABLE engine.flaky_build_watermark (
    repo       TEXT   PRIMARY KEY,
    run_id     BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
