-- Durable evidence for bound conversational consent. Existing rows remain unapproved.
ALTER TABLE conversation_message_entries ADD COLUMN quick_reply_json TEXT;
ALTER TABLE conversation_message_entries ADD COLUMN confirmation_proposal_id UUID;
ALTER TABLE conversation_message_entries ADD CONSTRAINT confirmation_requires_selection
    CHECK (confirmation_proposal_id IS NULL OR quick_reply_json IS NOT NULL);
CREATE UNIQUE INDEX uq_message_confirmation_proposal
    ON conversation_message_entries(user_id, conversation_id, confirmation_proposal_id)
    WHERE confirmation_proposal_id IS NOT NULL;
