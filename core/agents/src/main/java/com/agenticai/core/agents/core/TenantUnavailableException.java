package com.agenticai.core.agents.core;

/**
 * #815 Governance follow-up (Fail-safe: platform + per-tenant kill switch) -
 * thrown by {@code AgentChatService#chat} when {@code TenantStatusGuard
 * #isUsable} rejects the calling tenant. This is the omnichannel-webhook
 * half of the kill switch: {@link com.agenticai.core.security.TenantContextFilter}
 * only ever runs for JWT-authenticated requests (web console / direct API),
 * so a stopped tenant's WhatsApp/Telegram/email/etc. messages would otherwise
 * sail straight through to the LLM and any enabled tools, completely
 * bypassing the same guard the JWT path already enforces. {@link #chat}
 * explicitly re-checks the guard itself so both paths funnel through one
 * chokepoint - see that method's javadoc.
 *
 * <p>Mapped to 403 in {@code GlobalExceptionHandler}, same status {@code
 * TenantContextFilter} already uses for the identical rejection on the JWT
 * path - one consistent meaning for "this tenant is not usable right now"
 * regardless of which path produced it.
 */
public class TenantUnavailableException extends RuntimeException {

    public TenantUnavailableException(String message) {
        super(message);
    }
}
