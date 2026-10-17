-- The plan meter, per account, as last seen on a response from Anthropic
-- (engine/README.md, "The usage meter"): every Messages API reply carries
-- anthropic-ratelimit-unified-* headers with the account's 5-hour and 7-day
-- utilization, the windows' reset times and an allowed/allowed_warning/
-- rejected status. The proxy in front of the sandboxes records them here.
-- Not a foreign key to ai_account: the engine's own token (no row there)
-- is metered under the name `engine`, and a removed account keeps its last
-- reading until it is written over.
CREATE TABLE engine.ai_account_usage (
    account               VARCHAR(100) PRIMARY KEY,
    five_hour_utilization DOUBLE PRECISION,               -- 0..1
    five_hour_status      VARCHAR(40),
    five_hour_reset       TIMESTAMP WITH TIME ZONE,
    seven_day_utilization DOUBLE PRECISION,
    seven_day_status      VARCHAR(40),
    seven_day_reset       TIMESTAMP WITH TIME ZONE,
    status                VARCHAR(40),                    -- the overall verdict; `rejected` means the request was refused
    http_status           INTEGER NOT NULL,
    requests              BIGINT NOT NULL DEFAULT 0,      -- responses seen for this account since the row was made
    observed_at           TIMESTAMP WITH TIME ZONE NOT NULL
);
