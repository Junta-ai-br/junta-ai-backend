package br.com.juntaai.integration.ai.model;

/** Frozen in the original successful receipt. Money is encoded exactly, never as a float. */
public record GoalRecognitionEvidence(
        String version, String action_id, String goal_id, long contribution_number,
        String before_amount, String after_amount, String target_amount,
        int recognized_before, int recognized_after, Integer new_milestone, String status
) {}
