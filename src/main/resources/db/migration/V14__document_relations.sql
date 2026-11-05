CREATE TABLE document_relations (
    id UUID PRIMARY KEY,
    source_document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    target_document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    relation_type VARCHAR(20) NOT NULL,
    detail JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT document_relations_distinct_documents CHECK (source_document_id <> target_document_id),
    CONSTRAINT document_relations_type_check CHECK (relation_type IN ('REFERENCES', 'CONFLICTS')),
    CONSTRAINT document_relations_conflicts_order_check CHECK (
        relation_type <> 'CONFLICTS' OR source_document_id < target_document_id
    ),
    CONSTRAINT document_relations_detail_check CHECK (
        (relation_type = 'REFERENCES' AND detail IS NULL)
        OR (
            relation_type = 'CONFLICTS'
            AND jsonb_typeof(detail) = 'object'
            AND detail IS NOT NULL
            AND detail ?& ARRAY['item', 'thisValue', 'otherValue']
            AND jsonb_typeof(detail->'item') = 'string'
            AND length(trim(detail->>'item')) > 0
            AND jsonb_typeof(detail->'thisValue') = 'string'
            AND length(trim(detail->>'thisValue')) > 0
            AND jsonb_typeof(detail->'otherValue') = 'string'
            AND length(trim(detail->>'otherValue')) > 0
        )
    )
);

CREATE UNIQUE INDEX document_relations_references_unique
    ON document_relations (source_document_id, target_document_id)
    WHERE relation_type = 'REFERENCES';

CREATE UNIQUE INDEX document_relations_conflicts_unique
    ON document_relations (source_document_id, target_document_id, (detail->>'item'))
    WHERE relation_type = 'CONFLICTS';

CREATE INDEX document_relations_source_idx ON document_relations (source_document_id);
CREATE INDEX document_relations_target_idx ON document_relations (target_document_id);
