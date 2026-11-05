ALTER TABLE document_versions ADD COLUMN extension VARCHAR(16);

UPDATE document_versions AS version
SET extension = document.extension
FROM documents AS document
WHERE document.id = version.document_id;

ALTER TABLE document_versions ALTER COLUMN extension SET NOT NULL;
