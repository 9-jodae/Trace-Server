ALTER TABLE conversation_messages ADD COLUMN sequence_number BIGSERIAL;
ALTER TABLE conversation_messages ADD CONSTRAINT conversation_messages_sequence_unique UNIQUE (sequence_number);

CREATE INDEX conversation_messages_conversation_order_idx ON conversation_messages (conversation_id, sequence_number);
