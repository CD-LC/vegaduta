package com.agenticai.core.agents.tools.code.dto;

import tools.jackson.databind.JsonNode;

/**
 * Dry-run request. {@code input} is arbitrary JSON - whatever the tenant's own program expects - so
 * it stays a {@link JsonNode} and is never coerced into a Map or a String on the way through.
 */
public record CustomCodeToolTestRequest(JsonNode input) {}
