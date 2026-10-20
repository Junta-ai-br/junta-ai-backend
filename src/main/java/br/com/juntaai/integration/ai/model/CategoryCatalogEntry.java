package br.com.juntaai.integration.ai.model;

import br.com.juntaai.entity.CategoryType;
import java.util.UUID;

/** Backend-owned category identity. No eligibility flags or client metadata. */
public record CategoryCatalogEntry(UUID id, String name, CategoryType type) {}
