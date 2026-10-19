package com.agenticai.core.common.quicktask;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * What Simple mode's one big box + channel picker + Run button sends.
 *
 * <p>{@code agentId} is optional - "customers should be able to select the
 * agents" - null keeps today's behavior ({@code
 * QuickTaskService#selectAgentOrThrow} auto-picks via {@code
 * AgentRelevanceSelector}), a non-null value routes straight to that
 * specific agent instead (still validated as belonging to this tenant, same
 * as every other {@code AgentService.get} call site).
 */
public record QuickTaskRequest(
    @NotBlank String message,
    @NotNull QuickTaskChannel channel,
    UUID agentId
) {
}
