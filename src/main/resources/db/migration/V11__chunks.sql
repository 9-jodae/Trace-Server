CREATE TABLE chunks (
    id UUID PRIMARY KEY,
    document_version_id UUID NOT NULL REFERENCES document_versions(id),
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    char_start INTEGER,
    char_end INTEGER,
    page INTEGER,
    location JSONB,
    embedding vector(1024),
    content_hash TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chunks_version_index_unique UNIQUE (document_version_id, chunk_index)
);

CREATE INDEX chunks_document_version_idx ON chunks (document_version_id);
CREATE INDEX chunks_embedding_idx ON chunks USING hnsw (embedding vector_cosine_ops);

GRANT SELECT, INSERT, UPDATE, DELETE ON chunks TO ai_app;
