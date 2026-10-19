package com.agenticai.core.agents.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * Autonomy/Guardrails Strategy Stage A. Empty/null {@code allowedToolNames}
 * clears the restriction (back to "no restriction, allow every normally-
 * resolved tool") - matches {@code Agent.allowedToolNames}'s semantics
 * exactly, see that field's javadoc.
 *
 * <p>Entry bounds are input validation only (blank/overlong names can never
 * match a resolved tool and would be silently dead entries). Whether a
 * well-formed name actually resolves is a SOFT check at the controller -
 * MCP tool names are discovered at runtime and a temporarily-down server
 * must not block the write.
 */
public record AgentToolAccessUpdateRequest(
    @Size(max = 256, message = "allowedToolNames may hold at most 256 entries")
    Set<@NotBlank(message = "tool names must not be blank")
        @Size(max = 128, message = "each tool name must be at most 128 characters") String> allowedToolNames
) {
}
