CREATE TABLE planner_simulations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    goal_name VARCHAR(150) NOT NULL CHECK (length(trim(goal_name)) > 0),
    goal_amount NUMERIC(19,2) NOT NULL CHECK (goal_amount > 0),
    deadline_months INTEGER NOT NULL CHECK (deadline_months BETWEEN 1 AND 1200),
    analysis_base VARCHAR(20) NOT NULL CHECK (analysis_base IN
        ('current_month', 'last_30_days', 'last_3_months', 'no_history')),
    monthly_amount NUMERIC(19,2) NOT NULL CHECK (monthly_amount > 0),
    estimated_months INTEGER NOT NULL CHECK (estimated_months BETWEEN 1 AND 12000),
    history_context JSONB CHECK (history_context IS NULL OR jsonb_typeof(history_context) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_planner_simulations_user_created ON planner_simulations(user_id, created_at DESC, id DESC);
