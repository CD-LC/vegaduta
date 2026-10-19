package com.agenticai.core.workflow;

/**
 * How a {@link WorkflowTriggerChannel#WEBHOOK} trigger authenticates its
 * caller. SHARED_SECRET is the original #163 behavior unchanged - a
 * per-workflow opaque token in the URL path ({@code POST
 * /webhook/workflows/{token}}), still the right fit for callers that can't
 * present a bearer token (Zapier, cron-job.org, a curl one-liner). OAUTH2
 * requires the caller to present a Keycloak-issued bearer JWT for this
 * tenant, carrying the {@code workflow-trigger} realm role, to {@code POST
 * /webhook/workflows/oidc/{workflowId}} instead - see
 * {@link WorkflowWebhookController}'s javadoc for exactly how that's
 * validated (the app's single shared {@code JwtDecoder} bean, manually
 * invoked, since {@code /webhook/**} bypasses the OAuth2 Resource Server
 * filter chain entirely).
 */
public enum WebhookAuthMode {
    SHARED_SECRET,
    OAUTH2;

    /**
     * Per-node workflow triggers boundary conversion. {@code WorkflowTriggerNode}
     * (owned by {@code core-omnichannel}'s {@code
     * com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService}, the
     * replacement for the old per-{@link com.agenticai.core.workflow.WorkflowDefinition}
     * webhook-auth-mode column) cannot reference this enum directly: {@code
     * core-omnichannel} has no dependency on {@code core-workflow} (see {@code
     * WorkflowTriggerChannel}'s javadoc for the same module-direction
     * constraint), so it stores the auth mode as a plain {@code String} and
     * every {@code core-workflow} caller (this module) converts back through
     * this method at the boundary - see {@code WorkflowService}/{@code
     * WorkflowWebhookController} for the call sites. Unrecognized/blank/null
     * input degrades to {@link #SHARED_SECRET} (the original, still-default
     * behavior) rather than throwing - a node whose auth mode was never
     * explicitly set should behave exactly like it always has.
     */
    public static WebhookAuthMode fromNullableName(String raw) {
        if (raw == null || raw.isBlank()) {
            return SHARED_SECRET;
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException e) {
            return SHARED_SECRET;
        }
    }
}
