-- Chat ingress identity. No expiration/purge of keys and no inferred legacy identity.
ALTER TABLE action_executions ADD CONSTRAINT uq_execution_owner_conversation
    UNIQUE (action_id, user_id, conversation_id);

CREATE TABLE conversation_message_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    idempotency_key UUID NOT NULL,
    conversation_id UUID NOT NULL,
    user_message_id UUID NOT NULL UNIQUE,
    mode VARCHAR(20) NOT NULL CHECK (mode IN ('NORMAL', 'RECOVERY_ONLY')),
    state VARCHAR(24) NOT NULL CHECK (state IN ('PROCESSING', 'ACTION_SELECTED', 'RECEIPT_PENDING', 'COMPLETED', 'FAILED')),
    interpretation_request_id VARCHAR(200) NOT NULL,
    pending_state_before TEXT,
    processing_deadline TIMESTAMPTZ NOT NULL,
    selected_action_id VARCHAR(200) UNIQUE,
    selected_action_json TEXT,
    receipt_action_id VARCHAR(200),
    response_message_id UUID,
    failure_code VARCHAR(60),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, idempotency_key),
    FOREIGN KEY (conversation_id, user_id) REFERENCES conversations(id, user_id),
    -- Admission reserves the key before inserting the original message in the same transaction.
    FOREIGN KEY (user_message_id, conversation_id) REFERENCES messages(id, conversation_id)
        DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (response_message_id, conversation_id) REFERENCES messages(id, conversation_id),
    FOREIGN KEY (receipt_action_id, user_id, conversation_id)
        REFERENCES action_executions(action_id, user_id, conversation_id),
    CHECK ((state = 'ACTION_SELECTED') = (selected_action_json IS NOT NULL)),
    CHECK (state <> 'ACTION_SELECTED' OR (mode = 'NORMAL' AND selected_action_id IS NOT NULL AND receipt_action_id IS NULL)),
    CHECK (state <> 'PROCESSING' OR (mode = 'NORMAL' AND selected_action_id IS NULL AND receipt_action_id IS NULL)),
    CHECK (state <> 'FAILED' OR (mode = 'NORMAL' AND selected_action_id IS NULL AND receipt_action_id IS NULL AND failure_code IS NOT NULL)),
    CHECK (state <> 'RECEIPT_PENDING' OR receipt_action_id IS NOT NULL),
    CHECK (state <> 'COMPLETED' OR response_message_id IS NOT NULL),
    CHECK (mode <> 'RECOVERY_ONLY' OR (selected_action_id IS NULL AND receipt_action_id IS NOT NULL AND state IN ('RECEIPT_PENDING', 'COMPLETED'))),
    CHECK (selected_action_id IS NULL OR receipt_action_id IS NULL OR selected_action_id = receipt_action_id),
    CHECK (receipt_action_id IS NULL OR mode = 'RECOVERY_ONLY' OR selected_action_id IS NOT NULL)
);
CREATE INDEX idx_message_entry_conversation ON conversation_message_entries(conversation_id);
CREATE INDEX idx_message_entry_receipt ON conversation_message_entries(receipt_action_id)
    WHERE receipt_action_id IS NOT NULL;
