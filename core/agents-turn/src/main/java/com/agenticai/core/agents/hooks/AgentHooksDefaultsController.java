package com.agenticai.core.agents.hooks;

import com.agenticai.core.agents.hooks.AgentHookDtos.TenantHooksResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The workspace's default hooks: they apply to every agent, always evaluate first, and cannot be
 * removed by an agent's own list. Reading and saving both need the guardrails authority (the agent
 * screen gets them as inherited hooks, URL-redacted), checked in {@link AgentHookConfigService}.
 */
@RestController
@RequestMapping("/api/settings/agent-hooks")
public class AgentHooksDefaultsController {

    private final AgentHookConfigService service;

    public AgentHooksDefaultsController(AgentHookConfigService service) {
        this.service = service;
    }

    @GetMapping
    public TenantHooksResponse get() {
        return service.getTenantHooks();
    }

    /**
     * Body: {@code {"hooks": [...], "webhookHosts": ["hooks.example.com"]}}. {@code hooks} must be
     * present; {@code webhookHosts} is optional and, when absent, the saved allow-list is kept.
     */
    @PutMapping
    public TenantHooksResponse put(@RequestBody JsonNode body) {
        return service.putTenantHooks(body);
    }
}
