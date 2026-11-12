-- Recognition is derived only for goals created with known coverage after this change.
-- Existing goals stay NULL; accumulated money cannot reconstruct contribution history.
ALTER TABLE goals ADD COLUMN confirmed_contribution_count BIGINT;
ALTER TABLE goals ADD COLUMN recognized_milestone INTEGER;
ALTER TABLE goals ADD CONSTRAINT goal_recognition_valid CHECK (
    (confirmed_contribution_count IS NULL AND recognized_milestone IS NULL)
    OR (confirmed_contribution_count IS NOT NULL AND confirmed_contribution_count >= 0
        AND recognized_milestone IS NOT NULL AND recognized_milestone IN (0,25,50,75,100))
);
