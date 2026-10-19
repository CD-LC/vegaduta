package com.agenticai.core.chat;

import com.agenticai.core.security.TenantContext;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.metadata.Usage;

/** Reads the opt-in attribution params (see {@link LlmMetricsAdvisor#AGENT_ID_PARAM}). */
final class LlmCallRequestParams {

    private LlmCallRequestParams() {}

    static UUID uuid(ChatClientRequest request, String key) {
        Object value = request.context().get(key);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    static String string(ChatClientRequest request, String key) {
        Object value = request.context().get(key);
        return value == null ? null : value.toString();
    }

    /**
     * The version pin prefers the explicit advisor param (set by {@code attributeTo} at spec-build
     * time, which survives thread hops) and falls back to the ThreadLocal for call sites that set
     * attribution params by hand.
     */
    static UUID agentVersionId(ChatClientRequest request) {
        UUID id = uuid(request, LlmMetricsAdvisor.AGENT_VERSION_ID_PARAM);
        return id != null ? id : com.agenticai.core.security.AgentVersionPin.current();
    }

    static String promptVersion(ChatClientRequest request) {
        String explicit = string(request, LlmMetricsAdvisor.PROMPT_VERSION_PARAM);
        return explicit != null && !explicit.isBlank()
                ? explicit
                : AgentInvocationObservation.promptVersionOf(request.prompt());
    }

    /**
     * Attaches the provider-reported prompt tokens to this call's own snapshot (by id, once).
     * Best-effort.
     */
    static void attachSnapshotUsage(ChatClientRequest request, String snapshotId, Usage usage) {
        try {
            String sessionId = string(request, LlmMetricsAdvisor.SESSION_ID_PARAM);
            if (snapshotId == null
                    || sessionId == null
                    || usage == null
                    || usage.getPromptTokens() == null) {
                return;
            }
            LlmRequestSnapshotStore.INSTANCE.attachPromptTokens(
                    TenantContext.current().map(TenantContext::getTenantId).orElse(null),
                    sessionId,
                    snapshotId,
                    usage.getPromptTokens().longValue());
        } catch (RuntimeException ignored) {
            // inspector is best-effort
        }
    }
}
