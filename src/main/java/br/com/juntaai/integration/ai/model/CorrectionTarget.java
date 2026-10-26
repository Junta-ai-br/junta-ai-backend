package br.com.juntaai.integration.ai.model;

import java.time.Instant;
import java.util.UUID;

/** User-owned transaction and the revision explicitly proposed for correction. */
public record CorrectionTarget(UUID id, Instant updated_at) {}
