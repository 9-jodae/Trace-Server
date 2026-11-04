CREATE ROLE ai_app NOLOGIN;
GRANT ai_app TO CURRENT_USER;

-- The database name differs between environments (docs199 in production, a random
-- Testcontainers name in tests), so grant CONNECT on whichever database this migration runs in.
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO ai_app', current_database());
END
$$;

GRANT USAGE ON SCHEMA public TO ai_app;

GRANT SELECT ON sources TO ai_app;
GRANT SELECT ON departments TO ai_app;
GRANT SELECT ON documents TO ai_app;
GRANT SELECT ON document_departments TO ai_app;
GRANT SELECT ON document_versions TO ai_app;
GRANT SELECT ON ingestion_jobs TO ai_app;
