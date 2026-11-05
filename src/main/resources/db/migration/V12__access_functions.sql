-- Mirrors AccessPolicy.canAccess / DocumentRepository.list() scope filtering exactly:
-- isAdmin OR (clearance ordinal <= scope clearance ordinal
--             AND (no department restriction OR department matches)
--             AND (no minimum position OR minimum position ordinal <= scope position ordinal))
CREATE FUNCTION accessible_document_versions(
    p_department_id UUID,
    p_position TEXT,
    p_clearance TEXT,
    p_is_admin BOOLEAN
) RETURNS TABLE (document_version_id UUID)
LANGUAGE sql STABLE AS $$
    SELECT dv.id
    FROM documents d
    JOIN document_versions dv ON dv.document_id = d.id AND dv.version_number = d.current_version
    WHERE d.deleted_at IS NULL
      AND (
        p_is_admin
        OR (
          CASE d.clearance WHEN 'PUBLIC' THEN 0 WHEN 'INTERNAL' THEN 1 WHEN 'CONFIDENTIAL' THEN 2 END
            <= CASE p_clearance WHEN 'PUBLIC' THEN 0 WHEN 'INTERNAL' THEN 1 WHEN 'CONFIDENTIAL' THEN 2 ELSE -1 END
          AND (
            NOT EXISTS (SELECT 1 FROM document_departments dd WHERE dd.document_id = d.id)
            OR EXISTS (
                SELECT 1 FROM document_departments dd
                WHERE dd.document_id = d.id AND dd.department_id = p_department_id
            )
          )
          AND (
            d.minimum_position IS NULL
            OR CASE d.minimum_position WHEN 'STAFF' THEN 0 WHEN 'SENIOR' THEN 1 WHEN 'MANAGER' THEN 2 WHEN 'DIRECTOR' THEN 3 END
               <= CASE p_position WHEN 'STAFF' THEN 0 WHEN 'SENIOR' THEN 1 WHEN 'MANAGER' THEN 2 WHEN 'DIRECTOR' THEN 3 ELSE -1 END
          )
        )
      );
$$;

CREATE FUNCTION accessible_chunks(
    p_department_id UUID,
    p_position TEXT,
    p_clearance TEXT,
    p_is_admin BOOLEAN
) RETURNS SETOF chunks
LANGUAGE sql STABLE AS $$
    SELECT c.*
    FROM chunks c
    JOIN accessible_document_versions(p_department_id, p_position, p_clearance, p_is_admin) av
      ON av.document_version_id = c.document_version_id;
$$;

-- Re-checks a previously stored evidence_chunk_ids set against current access (for JEV context reuse).
CREATE FUNCTION filter_accessible_chunk_ids(
    p_department_id UUID,
    p_position TEXT,
    p_clearance TEXT,
    p_is_admin BOOLEAN,
    p_chunk_ids UUID[]
) RETURNS SETOF UUID
LANGUAGE sql STABLE AS $$
    SELECT c.id
    FROM chunks c
    JOIN accessible_document_versions(p_department_id, p_position, p_clearance, p_is_admin) av
      ON av.document_version_id = c.document_version_id
    WHERE c.id = ANY (p_chunk_ids);
$$;

GRANT EXECUTE ON FUNCTION accessible_document_versions(UUID, TEXT, TEXT, BOOLEAN) TO ai_app;
GRANT EXECUTE ON FUNCTION accessible_chunks(UUID, TEXT, TEXT, BOOLEAN) TO ai_app;
GRANT EXECUTE ON FUNCTION filter_accessible_chunk_ids(UUID, TEXT, TEXT, BOOLEAN, UUID[]) TO ai_app;
