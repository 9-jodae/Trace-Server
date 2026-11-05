-- Keep the existing four-argument signatures: defaults on this overload would make
-- existing four-argument calls ambiguous. NULL explicitly means no filter.
CREATE FUNCTION accessible_document_versions(
    p_department_id UUID, p_position TEXT, p_clearance TEXT, p_is_admin BOOLEAN,
    p_extensions TEXT[], p_document_types TEXT[], p_source_kinds TEXT[],
    p_authored_from DATE, p_authored_to DATE
) RETURNS TABLE (document_version_id UUID)
LANGUAGE sql STABLE AS $$
    SELECT av.document_version_id
    FROM accessible_document_versions(p_department_id, p_position, p_clearance, p_is_admin) av
    JOIN document_versions dv ON dv.id = av.document_version_id
    JOIN documents d ON d.id = dv.document_id
    JOIN sources s ON s.id = d.source_id
    WHERE (p_extensions IS NULL OR d.extension = ANY(p_extensions))
      AND (p_document_types IS NULL OR d.document_type = ANY(p_document_types))
      AND (p_source_kinds IS NULL OR s.kind = ANY(p_source_kinds))
      AND (p_authored_from IS NULL OR d.authored_at >= p_authored_from)
      AND (p_authored_to IS NULL OR d.authored_at <= p_authored_to);
$$;

CREATE FUNCTION accessible_chunks(
    p_department_id UUID, p_position TEXT, p_clearance TEXT, p_is_admin BOOLEAN,
    p_extensions TEXT[], p_document_types TEXT[], p_source_kinds TEXT[],
    p_authored_from DATE, p_authored_to DATE
) RETURNS SETOF chunks
LANGUAGE sql STABLE AS $$
    SELECT c.* FROM chunks c
    JOIN accessible_document_versions(
        p_department_id, p_position, p_clearance, p_is_admin,
        p_extensions, p_document_types, p_source_kinds, p_authored_from, p_authored_to
    ) av ON av.document_version_id = c.document_version_id;
$$;

GRANT EXECUTE ON FUNCTION accessible_document_versions(UUID, TEXT, TEXT, BOOLEAN, TEXT[], TEXT[], TEXT[], DATE, DATE) TO ai_app;
GRANT EXECUTE ON FUNCTION accessible_chunks(UUID, TEXT, TEXT, BOOLEAN, TEXT[], TEXT[], TEXT[], DATE, DATE) TO ai_app;
