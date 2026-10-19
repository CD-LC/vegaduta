package com.agenticai.core.agents.tools.openapi.dto;

/** Console "Try it" - the JSON an agent would send as the tool input. Blank means {@code {}}. */
public record OpenApiToolTestRequest(String inputJson) {}
