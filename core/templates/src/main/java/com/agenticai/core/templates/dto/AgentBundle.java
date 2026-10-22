package com.agenticai.core.templates.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * A portable, shareable agent: one JSON file that carries an agent's design and nothing else. It
 * has the same allow-listed shape as a template snapshot ({@code agent} is a {@code
 * TemplateSnapshotService.AgentSnapshot}), so a bundle never carries a secret, a knowledge-base id
 * or a tenant value. {@code format} names the version of this file format.
 */
public record AgentBundle(
        @NotBlank @Size(max = 64) String format,
        @NotBlank(message = "title is required") @Size(max = 200) String title,
        @Size(max = 4000) String summary,
        @Size(max = 64) String category,
        List<@Size(max = 40) String> tags,
        @NotNull(message = "agent is required") JsonNode agent) {}
