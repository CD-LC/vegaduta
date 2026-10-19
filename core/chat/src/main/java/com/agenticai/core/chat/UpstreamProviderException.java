package com.agenticai.core.chat;

/**
 * Wraps a failed call to an LLM provider (billing, rate limit, invalid
 * model, auth, etc.) with the upstream HTTP status and a clean, extracted
 * message - so callers of our API see something actionable instead of a
 * generic 500. See {@link ProviderErrorTranslator}.
 *
 * <p>Direct feedback ("I want to display the proper meaningful messages to
 * consumer, not raw http responses...even if there is an issue from llm
 * response") - carries TWO distinct messages, not one:
 *
 * <ul>
 *   <li>{@link #getMessage()} - the detailed, technical version (upstream
 *   status + the real provider-extracted reason, e.g. "The LLM provider
 *   rejected the request (HTTP 429): rate_limit_exceeded: ..."). This is
 *   what server-side logs and the admin-only LLM Calls log see - deliberately
 *   NOT sanitized, since #678's whole fix was making sure this real detail
 *   survives translation instead of collapsing to a generic status.</li>
 *   <li>{@link #getConsumerMessage()} - a short, plain-language, actionable
 *   sentence with no HTTP status codes, provider error codes, or raw JSON in
 *   it, classified from the upstream status (see {@code
 *   ProviderErrorTranslator#classify}). This is what {@code
 *   GlobalExceptionHandler#handleUpstreamProvider} actually returns to API
 *   callers - the tenant console shows THIS in chat/quick-task/playground
 *   error surfaces, never {@link #getMessage()}.</li>
 * </ul>
 */
public class UpstreamProviderException extends RuntimeException {

    private final int upstreamStatus;
    private final String consumerMessage;
    private final String providerName;
    private final boolean edgeAiRecoverable;

    public UpstreamProviderException(int upstreamStatus, String message, String consumerMessage) {
        this(upstreamStatus, message, consumerMessage, null);
    }

    /**
     * @param providerName the {@code ProviderName} (core/providers) this call was made against,
     *     e.g. {@code "GOOGLE_VERTEX"}, if known at the {@link ProviderErrorTranslator} call site
     *     - null when untranslated call sites use the 3-arg constructor. Deliberately a plain
     *     {@code String}, not the {@code ProviderName} enum itself, so this module doesn't need
     *     a compile dependency on core/providers just to carry it - {@code DiagnosticService}
     *     (core/app, see {@code com.agenticai.core.audit.IssueSource#UPSTREAM_PROVIDER_FAILURE}'s
     *     javadoc) only ever needs it as a stable {@code PlatformIssue.sourceId} string, never as
     *     a typed enum.
     */
    public UpstreamProviderException(int upstreamStatus, String message, String consumerMessage, String providerName) {
        this(upstreamStatus, message, consumerMessage, providerName, false);
    }

    /**
     * @param edgeAiRecoverable whether the client should attempt on-device Edge
     *     AI before showing this as a user-facing error, rather than surfacing
     *     it directly - see {@link #isEdgeAiRecoverable()}. {@code false} for
     *     every ordinary per-provider failure (a single BYOK credential/model
     *     being down is not "the platform has nothing left to try", it's just
     *     that one call failing); {@code true} only where a subtype
     *     deliberately opts in (currently only {@code
     *     com.agenticai.core.agents.hosted.HostedPoolExhaustedException}, once the
     *     ENTIRE platform-hosted pool - including its own same-turn self-heal
     *     retry - has been exhausted).
     */
    protected UpstreamProviderException(
        int upstreamStatus, String message, String consumerMessage, String providerName, boolean edgeAiRecoverable
    ) {
        super(message);
        this.upstreamStatus = upstreamStatus;
        this.consumerMessage = consumerMessage;
        this.providerName = providerName;
        this.edgeAiRecoverable = edgeAiRecoverable;
    }

    public int getUpstreamStatus() {
        return upstreamStatus;
    }

    /** Plain-language, no-raw-detail message safe to show directly to an end user. */
    public String getConsumerMessage() {
        return consumerMessage;
    }

    /** May be null - see the 4-arg constructor's javadoc. */
    public String getProviderName() {
        return providerName;
    }

    /**
     * Server-to-client signal for the "Selected Model -> Platform LLM -> Edge
     * AI -> user-facing error" recovery chain: Edge AI (on-device WebLLM/etc.)
     * can only run in the browser, so this server-side exception can't invoke
     * it itself - it can only tell the client whether this failure is the
     * "the whole platform-hosted pool is down, fall back to on-device" shape
     * ({@code true}) or an ordinary single-call failure the client should just
     * show ({@code false}). Read by {@code GlobalExceptionHandler}'s response
     * body (field {@code edgeAiRecoverable}); the client is expected to attempt
     * Edge AI before rendering an error whenever that field is {@code true}.
     */
    public boolean isEdgeAiRecoverable() {
        return edgeAiRecoverable;
    }
}
