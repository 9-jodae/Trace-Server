CREATE TABLE storage_cleanup_jobs (
    id UUID PRIMARY KEY,
    storage_key VARCHAR(1024) NOT NULL UNIQUE,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_attempt_at TIMESTAMPTZ
);
