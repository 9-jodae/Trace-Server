ALTER TABLE documents ADD COLUMN owner_department_id UUID REFERENCES departments(id);

CREATE INDEX documents_owner_department_idx ON documents (owner_department_id) WHERE deleted_at IS NULL;
