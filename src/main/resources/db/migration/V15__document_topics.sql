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

-- Keep direct table access away from the AI role; replacement is atomic and scoped by version.
REVOKE INSERT, DELETE ON document_topics FROM ai_app;

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

CREATE FUNCTION replace_document_topics(p_document_version_id UUID, p_topics TEXT[])
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF p_topics IS NULL OR cardinality(p_topics) > 3 THEN
        RAISE EXCEPTION 'A document version can have at most three topics'
            USING ERRCODE = 'check_violation';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM unnest(p_topics) AS input(topic)
        WHERE topic IS NULL OR length(trim(topic)) NOT BETWEEN 1 AND 100
    ) THEN
        RAISE EXCEPTION 'Topics must contain between one and one hundred characters'
            USING ERRCODE = 'check_violation';
    END IF;

    PERFORM id
    FROM public.document_versions
    WHERE id = p_document_version_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Document version does not exist'
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    DELETE FROM public.document_topics
    WHERE document_version_id = p_document_version_id;

    INSERT INTO public.document_topics (document_version_id, topic)
    SELECT p_document_version_id, topic
    FROM unnest(p_topics) AS input(topic);
END;
$$;

REVOKE ALL ON FUNCTION replace_document_topics(UUID, TEXT[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION replace_document_topics(UUID, TEXT[]) TO ai_app;
