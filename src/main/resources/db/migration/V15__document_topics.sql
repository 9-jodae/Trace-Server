CREATE TABLE document_topics (
    document_version_id UUID NOT NULL REFERENCES document_versions(id) ON DELETE CASCADE,
    topic TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT document_topics_topic_not_blank CHECK (length(trim(topic)) > 0)
);

CREATE UNIQUE INDEX document_topics_version_topic_unique
    ON document_topics (document_version_id, lower(trim(topic)));

CREATE INDEX document_topics_topic_version_idx
    ON document_topics (lower(trim(topic)), document_version_id);

-- Topic ingestion can replace a version's topics, but the AI role cannot read them.
GRANT INSERT, DELETE ON document_topics TO ai_app;
