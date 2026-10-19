package com.agenticai.core.sharing;

/**
 * Thrown whenever a caller lacks sufficient access to a shared resource -
 * mapped to HTTP 404 (see {@code GlobalExceptionHandler#handleResourceAccessDenied}
 * in {@code core/app}), NOT 403, by deliberate design.
 *
 * <p><b>Anti-enumeration:</b> this is the exact same principle already
 * established in this codebase for tenant-isolation violations
 * ({@code AgentService#get} throws {@code AgentNotFoundException} - also a
 * 404 - for an agent id outside the caller's own tenant schema) and for the
 * external agent-share feature ({@code AgentShareController}, which returns
 * indistinguishable responses for "unknown share id" vs "revoked share id").
 * A user with no access to a private resource must get "not found," never
 * "forbidden" - a 403 would confirm the resource exists at all, which is
 * itself a data leak within a tenant (e.g. "this agent id is real, you're
 * just not on it").
 *
 * <p>The message is deliberately generic and constant - never interpolate
 * the resource id, the resource type, or any detail about WHY access was
 * denied (wrong owner? no grant? insufficient grant level?) into it. Every
 * one of those details would itself leak information a denied caller isn't
 * entitled to.
 */
public class ResourceAccessDeniedException extends RuntimeException {

    private static final String GENERIC_MESSAGE = "Resource not found.";

    public ResourceAccessDeniedException() {
        super(GENERIC_MESSAGE);
    }
}
