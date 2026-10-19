package com.agenticai.core.agents.capability;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.providers.ProviderName;
import org.springframework.stereotype.Component;

/**
 * xAI's server-side Agent Tools (web_search/x_search/code_execution, run and
 * billed by xAI itself, outside this platform's guardrail wrapping) - the
 * capability-ized form of the old {@code xai_server_tools_enabled} column +
 * {@code PATCH /api/agents/{id}/xai-server-tools} endpoint.
 *
 * <p>{@link #appliesTo} is the server-side replacement for the workbench's
 * old {@code providerName === "GROK"} gate: the capability simply does not
 * appear in the catalog for non-Grok agents, instead of the UI hiding a
 * hardcoded section.
 */
@Component
public class XaiServerToolsCapability implements AgentCapability {

    @Override
    public String key() {
        return "xai-server-tools";
    }

    @Override
    public String label() {
        return "xAI server tools";
    }

    @Override
    public String description() {
        return "Let xAI run web search, X search and code execution server-side for this Grok agent. "
            + "Billed by xAI per invocation and not wrapped by this platform's guardrails.";
    }

    @Override
    public String category() {
        return "tools";
    }

    @Override
    public boolean appliesTo(Agent agent) {
        return agent.getProviderName() == ProviderName.GROK;
    }

    @Override
    public CapabilityAvailability availability() {
        // Applicability (a GROK-provider agent) already implies the tenant has
        // a working xAI credential - the agent could not have been created
        // otherwise. No separate tenant setup step exists for this one.
        return CapabilityAvailability.AVAILABLE;
    }

    @Override
    public boolean isEnabled(Agent agent) {
        return agent.isXaiServerToolsEnabled();
    }

    @Override
    public void apply(Agent agent, boolean enabled) {
        agent.setXaiServerToolsEnabled(enabled);
    }
}
