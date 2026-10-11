-- Internal backend evidence; no consent or authority is obtained from AI IDs.
ALTER TABLE conversations ADD CONSTRAINT uq_conversation_owner UNIQUE (id, user_id);
ALTER TABLE messages ADD CONSTRAINT uq_message_conversation UNIQUE (id, conversation_id);

CREATE TABLE action_executions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_id VARCHAR(200) NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL,
    user_message_id UUID NOT NULL,
    request_id VARCHAR(200) NOT NULL,
    action_json TEXT NOT NULL,
    receipt_json TEXT,
    status VARCHAR(20),
    effect_id UUID,
    pending_state_before TEXT,
    composition_pending BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (conversation_id, user_id) REFERENCES conversations(id, user_id) ON DELETE CASCADE,
    FOREIGN KEY (user_message_id, conversation_id) REFERENCES messages(id, conversation_id) ON DELETE CASCADE,
    CHECK ((status IS NULL AND receipt_json IS NULL) OR
           (status IN ('SUCCESSFUL', 'REJECTED', 'UNAVAILABLE') AND receipt_json IS NOT NULL)),
    CHECK (status IS DISTINCT FROM 'SUCCESSFUL' OR effect_id IS NOT NULL)
);
CREATE INDEX idx_action_composition ON action_executions(user_id, conversation_id, created_at)
    WHERE composition_pending;

-- A reservation may exist inside the transaction, never as a committed receipt.
CREATE FUNCTION check_action_execution_terminal() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM action_executions WHERE id = NEW.id
               AND (status IS NULL OR receipt_json IS NULL)) THEN
        RAISE EXCEPTION 'action_execution_incomplete';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER action_execution_terminal
    AFTER INSERT OR UPDATE ON action_executions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION check_action_execution_terminal();
