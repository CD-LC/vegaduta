package com.agenticai.core.agents.assist.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * One "Ask AI" drafting request from any description/prompt field in the
 * console - see {@code AskAiHelper.js} on the frontend. Deliberately generic
 * (not tied to agents, workflows, or knowledge collections specifically) so
 * one backend endpoint serves every free-text field in the product.
 *
 * @param fieldLabel what this field is, in plain language, e.g. "Agent
 *     system prompt" or "Knowledge collection description" - tells the model
 *     what kind of text to produce.
 * @param instruction the user's own rough idea of what they want, e.g.
 *     "a support agent that handles refunds and order status".
 * @param currentValue the field's current contents, if any - when present,
 *     the model improves/extends it rather than starting from scratch.
 */
public record AssistRequest(
    @NotBlank String fieldLabel,
    @NotBlank String instruction,
    String currentValue
) {}
