package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.tunables.BehaviourSettings;
import com.agenticai.core.security.TenantScope;
import java.util.UUID;

/**
 * The three numbers of {@code app.chat.finish.*} a turn acts on - whether the provider's finish reason is acted on at
 * all, how many passes may finish a reply cut off at the output limit, how many may retry an unusable tool call - as
 * they apply to one turn's tenant and agent: the platform value unless the tenant or the agent set
 * {@code chat.finish.enabled} / {@code chat.finish.maxContinuations} / {@code chat.finish.maxToolCallRetries} in the
 * settings catalog. The wording (nudges, notices) stays {@link ChatFinishProperties}.
 */
record TurnFinishLimits(boolean enabled, int maxContinuations, int maxToolCallRetries) {

    static final String ENABLED_KEY = "chat.finish.enabled";
    static final String MAX_CONTINUATIONS_KEY = "chat.finish.maxContinuations";
    static final String MAX_TOOL_CALL_RETRIES_KEY = "chat.finish.maxToolCallRetries";

    /** Read once per turn (or pass), never per token: the catalog caches per tenant but a decision needs one answer. */
    static TurnFinishLimits resolve(BehaviourSettings behaviour, ChatFinishProperties platform, TenantScope scope, UUID agentId) {
        return new TurnFinishLimits(
                behaviour.bool(ENABLED_KEY, scope, agentId, platform::isEnabled),
                Math.max(0, behaviour.integer(MAX_CONTINUATIONS_KEY, scope, agentId, platform::getMaxContinuations)),
                Math.max(0, behaviour.integer(MAX_TOOL_CALL_RETRIES_KEY, scope, agentId, platform::getMaxToolCallRetries)));
    }
}
