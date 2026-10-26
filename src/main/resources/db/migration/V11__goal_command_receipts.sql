-- Durable HTTP retries; the goal and its receipt commit in the same transaction.
CREATE TABLE goal_command_receipts (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    command_id UUID NOT NULL,
    request_json TEXT NOT NULL,
    response_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, command_id)
);
