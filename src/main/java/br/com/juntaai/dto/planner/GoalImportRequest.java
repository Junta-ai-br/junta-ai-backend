package br.com.juntaai.dto.planner;

import java.time.LocalDate;

/** Deadline is chosen explicitly, never inferred from simulation months. */
public record GoalImportRequest(LocalDate deadline, boolean confirmReimport) {}
