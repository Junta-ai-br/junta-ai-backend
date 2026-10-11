-- Internal reference to the single accepted composition, not a remote-call reservation.
ALTER TABLE action_executions ADD COLUMN composition_message_id UUID;
ALTER TABLE action_executions ADD CONSTRAINT fk_composition_message
    FOREIGN KEY (composition_message_id, conversation_id)
    REFERENCES messages(id, conversation_id);
