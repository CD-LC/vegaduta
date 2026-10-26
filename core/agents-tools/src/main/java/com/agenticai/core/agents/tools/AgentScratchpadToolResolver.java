package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.tools.scratchpad.AgentScratchpadService;
import com.agenticai.core.agents.tools.scratchpad.ScratchpadProperties;
import com.agenticai.core.agents.tools.scratchpad.ScratchpadSettings;
import com.agenticai.core.agents.tools.scratchpad.ScratchpadToolCallback;
import com.agenticai.core.agents.tunables.BehaviourSettings;
import com.agenticai.core.security.TenantScope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tool-resolution group for the agent scratchpad ({@code scratchpad_write}, {@code
 * scratchpad_read}, {@code scratchpad_list}, {@code scratchpad_delete}). Not a Spring bean:
 * constructed by {@link ToolResolverConfiguration}; a {@code scratchpad} {@link ToolSource} wraps
 * {@link #resolveScratchpadTools}.
 *
 * <p>Offer rule for a chat turn: {@code app.tools.scratchpad.enabled} is on AND the service bean
 * exists. There is no per-agent opt-in - the pad is the agent's own working memory, classified
 * TENANT_SCOPED internal state with no approval, and the usual allow-list filter still applies for
 * agents that carry one. Each callback gets the turn's agent id and session id at build time
 * (identity is never a tool argument); the session string is mapped inside the callback to the
 * session pad's stable UUID.
 */
final class AgentScratchpadToolResolver {

    private final AgentToolGuard toolGuard;
    private final ObjectProvider<AgentScratchpadService> scratchpadService;
    private final ScratchpadProperties properties;
    private final ObjectProvider<BehaviourSettings> behaviour;

    AgentScratchpadToolResolver(
            AgentToolGuard toolGuard,
            ObjectProvider<AgentScratchpadService> scratchpadService,
            ScratchpadProperties properties,
            ObjectProvider<BehaviourSettings> behaviour) {
        this.toolGuard = toolGuard;
        this.scratchpadService = scratchpadService;
        this.properties = properties;
        this.behaviour = behaviour;
    }

    /** Platform flag AND any tenant/agent override through the behaviour catalog ({@code tools.scratchpad.enabled}). */
    boolean enabled(UUID agentId) {
        if (properties == null || !properties.isEnabled()) {
            return false;
        }
        BehaviourSettings settings = behaviour == null ? null : behaviour.getIfAvailable();
        if (settings == null) {
            return true;
        }
        Boolean scoped = settings.boolOrNull(ScratchpadSettings.ENABLED_KEY, TenantScope.capture(), agentId);
        return scoped == null || scoped;
    }

    /** The four scratchpad tools, or nothing: flag off, service absent, or no agent on the turn. */
    List<ToolCallback> resolveScratchpadTools(
            UUID agentId,
            String sessionId,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous) {
        if (agentId == null || !enabled(agentId)) {
            return List.of();
        }
        AgentScratchpadService service =
                scratchpadService == null ? null : scratchpadService.getIfAvailable();
        if (service == null) {
            return List.of();
        }
        List<ToolCallback> out = new ArrayList<>(ScratchpadToolCallback.Kind.values().length);
        for (ScratchpadToolCallback.Kind kind : ScratchpadToolCallback.Kind.values()) {
            out.add(
                    toolGuard.guardAndLog(
                            new ScratchpadToolCallback(service, kind, agentId, sessionId),
                            agentId,
                            sessionId,
                            stepCounter,
                            userMessage,
                            chatClient,
                            rigorous));
        }
        return out;
    }
}
