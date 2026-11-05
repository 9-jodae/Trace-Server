CREATE TABLE document_topics (
    document_version_id UUID NOT NULL REFERENCES document_versions(id) ON DELETE CASCADE,
    topic TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT document_topics_topic_length CHECK (length(trim(topic)) BETWEEN 1 AND 100)
);

CREATE UNIQUE INDEX document_topics_version_topic_unique
    ON document_topics (document_version_id, lower(trim(topic)));

CREATE INDEX document_topics_topic_version_idx
    ON document_topics (lower(trim(topic)), document_version_id);

-- Topic ingestion can replace a version's topics, but the AI role cannot read them.
GRANT INSERT, DELETE ON document_topics TO ai_app;

-- Serialize writes per version and reject more than three topics, even for direct AI role inserts.
CREATE FUNCTION enforce_document_topic_limits()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    existing_count INTEGER;
BEGIN
    PERFORM id
    FROM public.document_versions
    WHERE id = NEW.document_version_id
    FOR UPDATE;

    SELECT count(*)
    INTO existing_count
    FROM public.document_topics
    WHERE document_version_id = NEW.document_version_id;

    IF existing_count >= 3 THEN
        RAISE EXCEPTION 'A document version can have at most three topics'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION enforce_document_topic_limits() FROM PUBLIC;

CREATE TRIGGER document_topics_limit_per_version
    BEFORE INSERT ON document_topics
    FOR EACH ROW
    EXECUTE FUNCTION enforce_document_topic_limits();
