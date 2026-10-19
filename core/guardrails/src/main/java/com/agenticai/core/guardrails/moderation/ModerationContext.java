package com.agenticai.core.guardrails.moderation;

import com.agenticai.core.security.TenantContext;
import java.util.UUID;

/**
 * Phase P6. The who/what/where around one piece of content being moderated -
 * supplied by whatever call site invokes {@link ModerationService}. Kept as a
 * plain value object so the moderation subsystem never has to reach back into
 * request-scoped state itself (it can, via {@link #fromCurrentTenant}, but the
 * explicit constructor keeps it unit-testable and usable off the request
 * thread, e.g. a background workflow step).
 *
 * @param tenantId the tenant whose policy applies; drives per-tenant config
 *     resolution. Never null in practice - {@link #fromCurrentTenant} falls
 *     back to {@code "unknown"} rather than null if called with no context.
 * @param plan the tenant's plan token (e.g. {@code FREE}/{@code PRO}), for
 *     per-plan pack enablement; may be null/blank if the caller doesn't know
 *     it, in which case only tenant-level and global config apply.
 * @param agentId the agent that produced/received the content; may be null
 *     for non-agent content paths.
 * @param sessionId the conversation/session id; may be null.
 * @param actorId the human/system actor to stamp on audit + review rows; may
 *     be null (recorded as an honest unknown, same posture as {@code
 *     AuditLogEntry}'s nullable actor).
 * @param direction free-form label for where in the flow this content sits
 *     (e.g. {@code "user_input"}, {@code "agent_output"}) - audit context
 *     only; may be null.
 */
public record ModerationContext(
    String tenantId,
    String plan,
    UUID agentId,
    String sessionId,
    String actorId,
    String direction
) {

    /**
     * Convenience builder from the current request's {@link TenantContext}
     * (tenant id + subject as actor). Plan/agent/session/direction are the
     * caller's to supply since {@link TenantContext} doesn't carry them.
     */
    public static ModerationContext fromCurrentTenant(String plan, UUID agentId, String sessionId, String direction) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse("unknown");
        String actorId = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return new ModerationContext(tenantId, plan, agentId, sessionId, actorId, direction);
    }
}
