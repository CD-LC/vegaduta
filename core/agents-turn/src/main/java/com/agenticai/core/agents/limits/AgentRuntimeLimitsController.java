package com.agenticai.core.agents.limits;

import com.agenticai.core.agents.runtime.RuntimeLimits;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.settings.SettingScope;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One agent's runtime-limit overrides: read them with the values they produce, replace them. A PUT replaces
 * the whole override (a field left out, or an empty body, goes back to the tenant / platform value); values
 * outside {@code app.agent-runtime.bounds.*} are refused with a 400 that names each one. Access is the agent's:
 * VIEWER reads, EDITOR writes (checked by {@code AgentService}).
 */
@RestController
@RequestMapping("/api/agents/{agentId}/runtime-limits")
public class AgentRuntimeLimitsController {

    private final AgentService agentService;
    private final AgentRuntimeSettingsResolver resolver;

    private final RuntimeLimitsHistory history;

    public AgentRuntimeLimitsController(AgentService agentService, AgentRuntimeSettingsResolver resolver) {
        this(agentService, resolver, null);
    }

    @Autowired
    public AgentRuntimeLimitsController(
            AgentService agentService, AgentRuntimeSettingsResolver resolver, RuntimeLimitsHistory history) {
        this.agentService = agentService;
        this.resolver = resolver;
        this.history = history;
    }

    @GetMapping
    public RuntimeLimitsView get(@PathVariable UUID agentId) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        return view(agentId);
    }

    @PutMapping
    public RuntimeLimitsView put(@PathVariable UUID agentId, @RequestBody(required = false) RuntimeLimits body) {
        // Authorization first: a non-editor must see 403, not a 400 that reveals the bounds check.
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        RuntimeLimits limits = body == null ? RuntimeLimits.none() : body;
        resolver.validate(limits);
        RuntimeLimits before = history == null ? null : agentService.getRuntimeLimits(agentId);
        agentService.setRuntimeLimits(agentId, limits);
        if (history != null) {
            history.record(SettingScope.AGENT, agentId, before, limits, "/api/agents/{id}/runtime-limits");
        }
        return view(agentId);
    }

    private RuntimeLimitsView view(UUID agentId) {
        return new RuntimeLimitsView(
                agentService.getRuntimeLimits(agentId),
                RuntimeLimitsView.Effective.of(resolver.forAgent(agentId)),
                resolver.bounds());
    }
}
