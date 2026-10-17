-- GET /jev/calls filters by use case newest first, and GET /jev/summary reads
-- a window of called_at (engine/README.md, "Jev decisions").
CREATE INDEX jev_call_use_case_called_at ON engine.jev_call (use_case, called_at);
CREATE INDEX jev_call_called_at ON engine.jev_call (called_at);
