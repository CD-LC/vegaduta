package com.agenticai.core.agents.hooks;

import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import java.util.List;
import java.util.UUID;

/**
 * Everything a hook evaluation needs to know about the run it belongs to.
 *
 * @param emitter the turn's step-event emitter when known; {@code null} lets a tool-level
 *     evaluation fall back to the emitter bound to the calling thread for the duration of the tool
 *     call
 * @param scope the tenant context captured where the run started, re-applied on any thread a hook
 *     runs on
 * @param webhookHosts the tenant's own webhook allow-list, merged with the operator's at call time
 */
public record HookCtx(
        UUID agentId,
        String sessionId,
        String tenantId,
        AgentStepEventEmitter emitter,
        TenantScope scope,
        List<String> webhookHosts) {

    public HookCtx {
        webhookHosts = webhookHosts == null ? List.of() : List.copyOf(webhookHosts);
        scope = scope == null ? TenantScope.capture() : scope;
    }

    /** Captures the calling thread's tenant context. */
    public static HookCtx capture(
            UUID agentId,
            String sessionId,
            AgentStepEventEmitter emitter,
            List<String> webhookHosts) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        return new HookCtx(
                agentId, sessionId, tenantId, emitter, TenantScope.capture(), webhookHosts);
    }

    /** The actor recorded on audit entries. */
    public String actor() {
        return "agent:" + agentId + ":session:" + sessionId;
    }

    /**
     * This context's emitter, else the one bound to the calling thread by the surrounding tool
     * call, else null.
     */
    AgentStepEventEmitter emitterOrCurrent() {
        if (emitter != null && emitter.isEnabled()) {
            return emitter;
        }
        return AgentStepEventEmitter.current().orElse(null);
    }
}
