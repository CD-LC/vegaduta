package com.agenticai.core.agents.hooks;

import com.agenticai.core.agents.hooks.AgentHookDtos.AgentHooksResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Read and replace one agent's lifecycle hooks. Thin on purpose: access (VIEWER to read; EDITOR
 * plus the guardrails authority to save), the feature flag, validation and every status code are
 * decided in {@link AgentHookConfigService}, which throws only types {@code GlobalExceptionHandler}
 * already maps - {@code IllegalArgumentException} (400), {@code ResourceAccessDeniedException}
 * (404) and {@code ResponseStatusException} (403, 409).
 *
 * <p>No {@code SecurityConfig} entry: the path falls through to {@code
 * anyRequest().authenticated()}; the stricter write rule is enforced in the service so it holds on
 * every transport.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/hooks")
public class AgentHooksController {

    private final AgentHookConfigService service;

    public AgentHooksController(AgentHookConfigService service) {
        this.service = service;
    }

    @GetMapping
    public AgentHooksResponse get(@PathVariable UUID agentId) {
        return service.getAgentHooks(agentId);
    }

    /**
     * Body: {@code {"hooks": [...]}}. The whole list is replaced; {@code []} clears it. The {@code
     * hooks} field must be present, so a client typo can never silently clear an agent's
     * governance.
     */
    @PutMapping
    public AgentHooksResponse put(@PathVariable UUID agentId, @RequestBody JsonNode body) {
        return service.putAgentHooks(agentId, body);
    }
}
