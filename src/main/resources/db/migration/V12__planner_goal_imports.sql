-- One current goal per saved simulation. Keep a tombstone when the goal is deleted.
CREATE TABLE planner_goal_imports (
    simulation_id UUID PRIMARY KEY REFERENCES planner_simulations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    goal_id UUID UNIQUE REFERENCES goals(id) ON DELETE SET NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
