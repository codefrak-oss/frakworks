-- Each approval the scan has acted on, so no scan or wake acts on it twice.
-- An approval is one `labeled` event: labelling again is a new one.
CREATE TABLE engine.handled_issue (
    full_name         VARCHAR(200) NOT NULL,
    issue             BIGINT       NOT NULL,
    label_event       BIGINT       NOT NULL,  -- GitHub's id of the labeled event
    flow              VARCHAR(40)  NOT NULL,
    outcome           VARCHAR(20)  NOT NULL,  -- started | refused
    detail            VARCHAR(400),
    process_instance  VARCHAR(64),
    handled_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (full_name, issue, label_event)
);
