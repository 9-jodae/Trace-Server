CREATE TABLE sources (
    id UUID PRIMARY KEY,
    kind VARCHAR(20) NOT NULL,
    name VARCHAR(255) NOT NULL,
    default_department_id UUID REFERENCES departments(id),
    default_clearance VARCHAR(30) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE documents (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES sources(id),
    external_id VARCHAR(1024) NOT NULL,
    title VARCHAR(255) NOT NULL,
    relative_path VARCHAR(1024) NOT NULL,
    extension VARCHAR(20) NOT NULL,
    document_type VARCHAR(30),
    author VARCHAR(255),
    authored_at DATE,
    clearance VARCHAR(30) NOT NULL,
    minimum_position VARCHAR(30),
    current_version INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT documents_source_path_unique UNIQUE (source_id, external_id)
);

CREATE TABLE document_departments (
    document_id UUID NOT NULL REFERENCES documents(id),
    department_id UUID NOT NULL REFERENCES departments(id),
    PRIMARY KEY (document_id, department_id)
);

CREATE TABLE document_versions (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id),
    version_number INTEGER NOT NULL,
    storage_key VARCHAR(1024) NOT NULL UNIQUE,
    file_size BIGINT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    uploaded_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT document_versions_number_unique UNIQUE (document_id, version_number)
);

CREATE TABLE ingestion_jobs (
    id UUID PRIMARY KEY,
    version_id UUID NOT NULL UNIQUE REFERENCES document_versions(id),
    status VARCHAR(20) NOT NULL,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX documents_active_source_idx ON documents (source_id) WHERE deleted_at IS NULL;
CREATE INDEX document_versions_document_idx ON document_versions (document_id, version_number DESC);
