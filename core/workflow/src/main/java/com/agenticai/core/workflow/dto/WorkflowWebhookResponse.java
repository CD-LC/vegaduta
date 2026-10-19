package com.agenticai.core.workflow.dto;

/**
 * Workflow webhook trigger (#163). {@code token} is null until a tenant
 * enables the webhook for the first time; once minted it never changes
 * across disable/re-enable, so a URL pasted into a third-party tool (a cron
 * service, Zapier, curl) keeps working after a temporary disable. The
 * frontend builds the full callable URL itself (API_BASE + "/webhook/workflows/" + token)
 * since this DTO has no notion of which host it's being served from.
 *
 * <p>Security audit 2026-07-29 (F2). {@code token} is a permanent,
 * unauthenticated, internet-reachable trigger for this workflow - treat it as
 * a credential, not an identifier. Two things changed:
 * <ul>
 *   <li>{@code GET /api/workflows/{id}/webhook}, the read that hands this
 *       record back, is now {@code ROLE_tenant-admin} in {@code
 *       SecurityConfig}, matching its POST/DELETE siblings. It was the only
 *       verb on that path left on {@code anyRequest().authenticated()}.</li>
 *   <li>{@link #tokenSuffix} is added as the masked form ({@code null} or the
 *       last 4 characters) so a UI can confirm <em>which</em> token is
 *       configured - "…4f9c" - without re-displaying the secret itself, the
 *       same "never return the raw key" posture {@code
 *       McpServerAccessGrantResponse} takes with {@code apiKeyDisplayPrefix}.
 *       {@code token} is still populated on this now-admin-only response:
 *       {@code WorkflowCanvas.tsx} reads {@code webhook.token} on canvas load
 *       to render its copyable trigger URL, and unlike the MCP grant flow
 *       there is no separate once-only reveal to fall back on (the mint
 *       endpoint is POST /webhook, which an admin cannot re-issue while the
 *       webhook is enabled). Dropping it here would have been a silent
 *       functional regression for admins, in a module this pass does not own
 *       the frontend for. Migrating the console to {@code tokenSuffix} plus
 *       an explicit reveal, then nulling {@code token} on this response, is
 *       the remaining follow-up.</li>
 * </ul>
 *
 * @param tokenSuffix last 4 characters of {@code token}, or null when no
 *     token has ever been minted. Safe to log or display.
 */
public record WorkflowWebhookResponse(String token, String tokenSuffix, boolean enabled) {

    /**
     * Builds the response from a raw token, deriving {@link #tokenSuffix} so
     * no call site has to (and so the masking rule lives in exactly one
     * place). A token shorter than 4 characters is never produced by {@code
     * WorkflowService#generateToken} (48 hex chars) but is handled rather
     * than allowed to throw on a hand-edited row.
     */
    public static WorkflowWebhookResponse of(String token, boolean enabled) {
        return new WorkflowWebhookResponse(token, suffixOf(token), enabled);
    }

    private static String suffixOf(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        return token.length() <= 4 ? token : token.substring(token.length() - 4);
    }
}
