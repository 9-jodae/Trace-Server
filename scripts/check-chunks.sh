#!/usr/bin/env bash
# Read-only current-version chunk and embedding status report. Compose loads .env without printing it.
set -euo pipefail

if [[ ! -f .env ]]; then
  echo "Error: .env must exist in the repository root." >&2
  exit 1
fi

docker compose -f compose.prod.yaml exec -T postgres sh -c \
  'exec psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off' <<'SQL'
\echo 'Current-version chunks and NULL embeddings by document'
SELECT d.id AS document_id, d.title, v.version_number,
       count(c.id) AS chunk_count,
       count(c.id) FILTER (WHERE c.embedding IS NULL) AS embedding_null_count
FROM documents d
JOIN document_versions v
  ON v.document_id = d.id AND v.version_number = d.current_version
LEFT JOIN chunks c ON c.document_version_id = v.id
WHERE d.deleted_at IS NULL
GROUP BY d.id, d.title, v.version_number
ORDER BY d.title, d.id;

\echo 'Current versions without chunks'
SELECT d.id AS document_id, d.title, v.version_number
FROM documents d
JOIN document_versions v
  ON v.document_id = d.id AND v.version_number = d.current_version
LEFT JOIN chunks c ON c.document_version_id = v.id
WHERE d.deleted_at IS NULL
GROUP BY d.id, d.title, v.version_number
HAVING count(c.id) = 0
ORDER BY d.title, d.id;

\echo 'Total NULL embeddings in current-version chunks'
SELECT count(*) FILTER (WHERE c.embedding IS NULL) AS embedding_null_count
FROM documents d
JOIN document_versions v
  ON v.document_id = d.id AND v.version_number = d.current_version
JOIN chunks c ON c.document_version_id = v.id
WHERE d.deleted_at IS NULL;
SQL
