package com.agenticai.core.agents.memory;

import com.agenticai.core.security.TenantContext;

import java.util.Optional;
import java.util.UUID;

/**
 * SEC-004: the session-memory id a client session id resolves to for the caller bound to this
 * thread. Session memory is only ever written under {@link SessionMemoryService#scopedSessionId}
 * (and its owner record is bound to that id), so any endpoint that reads a conversation back by the
 * id the client knows has to come through here; looking up the raw id finds nothing.
 *
 * <p>Same components {@code AgentChatService#chat} hashes for an authenticated caller: the
 * TenantContext tenant, the agent the caller addressed, and the caller's subject (the JWT {@code
 * sub}, which is what {@code principal.getName()} is in {@code AgentController#chat}; for a
 * delegation token both are the delegating user).
 */
public final class CallerSessionIds {

    private CallerSessionIds() {}

    /**
     * The caller-scoped memory id for {@code clientSessionId} on {@code agentId}, or empty when no
     * caller is bound (no TenantContext, or no subject): such a request owns no conversation.
     */
    public static Optional<String> scopedForCurrentCaller(UUID agentId, String clientSessionId) {
        return TenantContext.current()
                .filter(ctx -> ctx.getSubject() != null && !ctx.getSubject().isBlank())
                .map(
                        ctx ->
                                SessionMemoryService.scopedSessionId(
                                        ctx.getTenantId(),
                                        agentId,
                                        ctx.getSubject(),
                                        clientSessionId));
    }
}
