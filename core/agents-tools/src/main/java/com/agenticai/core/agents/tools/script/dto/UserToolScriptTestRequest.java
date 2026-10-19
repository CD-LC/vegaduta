package com.agenticai.core.agents.tools.script.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Dry-run request for {@code UserToolScriptController#test} - see {@code
 * UserToolScriptService#test}.
 */
public record UserToolScriptTestRequest(@NotBlank String expression, String sampleInputJson) {}
