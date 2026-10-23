package com.agenticai.core.chat;

import java.util.Locale;

/**
 * The one place that decides "is this provider failure worth another PROVIDER's attempt, or is
 * switching guaranteed not to help?". It used to live in the turn module ({@code
 * HostedFailoverPolicy}) where the hosted chain and the BYOK fallback use it; it moved here so the
 * client-side tool-loop endpoint ({@code /api/completions/agent-turn}), which sits in this module
 * and cannot see the turn module, applies the same rule instead of replaying an input-shaped
 * failure against every row of the pool.
 *
 * <h4>Classification</h4>
 *
 * <ul>
 *   <li><b>Never retryable</b>: a property of the USER'S INPUT, not of the provider - content-policy
 *       or moderation refusals, context-length or payload-too-large, and semantic rejections of the
 *       request as sent (413/422). Every provider would reject it the same way, and the consumer
 *       message {@link ProviderErrorTranslator#translate} already produced for these is the
 *       actionable one.
 *   <li><b>Retryable</b>: a property of THIS provider - 401/403 (its key), 402 (its billing), 404 (its
 *       model id or endpoint), 408/429 (its capacity), every 5xx, timeout and connection error. Each
 *       row carries its own key, endpoint and model, so the next one can succeed.
 *   <li><b>400</b> is retryable: from an OpenAI-compatible host it is overwhelmingly "this provider
 *       does not accept that model or parameter". The genuinely input-shaped 400s (content policy,
 *       context length) are caught by the phrase pass first.
 * </ul>
 *
 * <p>Anything that is not an {@link UpstreamProviderException} is never retryable: a guardrail
 * violation, a tool step limit or a moderation block did not come from a provider.
 */
public final class ProviderFailurePolicy {

    /**
     * Phrases every major provider (OpenAI / Anthropic / Gemini / OpenRouter / Groq) uses for the two
     * "the user's own input is the problem" families. Deliberately narrow, same posture as {@code
     * ProviderErrorTranslator#classify}'s keyword sniffing: an unrecognised detail falls through to
     * the status-based rule rather than being guessed at.
     */
    private static final String[] INPUT_FAULT_PHRASES = {
        "content_policy",
        "content policy",
        "content management policy",
        "content filter",
        "content_filter",
        "prohibited_content",
        "blocked by safety",
        "safety_ratings",
        "moderation",
        "context length",
        "context_length",
        "maximum context",
        "context window",
        "context_window",
        "too many tokens",
        "reduce the length",
        "string too long",
    };

    private ProviderFailurePolicy() {}

    /** True when another provider deserves a chance at this turn; see the class javadoc. */
    public static boolean isRetryableOnAnotherProvider(Throwable error) {
        if (!(error instanceof UpstreamProviderException upstream)) {
            return false;
        }
        // getMessage(), not getConsumerMessage(): the technical one still carries the provider's own
        // extracted reason ("model ... not found", "content_policy_violation", ...), which is what
        // this needs - the consumer message has had that detail stripped out.
        String detail =
                upstream.getMessage() == null ? "" : upstream.getMessage().toLowerCase(Locale.ROOT);
        for (String phrase : INPUT_FAULT_PHRASES) {
            if (detail.contains(phrase)) {
                return false;
            }
        }
        int status = upstream.getUpstreamStatus();
        return switch (status) {
            // The user's own request is malformed/oversized for ANY model.
            case 413, 422 -> false;
            // This provider's key, account, model id, endpoint, or capacity.
            case 400, 401, 402, 403, 404, 408, 409, 425, 429 -> true;
            // Everything else: server-side upstream failures (incl. the 502 ProviderErrorTranslator
            // falls back to for connection errors and unstructured exceptions) are retryable;
            // unknown 2xx/3xx shapes, which should never reach here, are not.
            default -> status >= 500;
        };
    }

    /**
     * Whether a failure should count toward the row's CIRCUIT BREAKER. Narrower than {@link
     * #isRetryableOnAnotherProvider}: the breaker is shared by every tenant on the pooled row, and a
     * retryable 400/409 is usually the caller's request shape (tool schema, replayed history,
     * unsupported parameter), which one tenant can repeat until the row is skipped for everyone.
     * Those still fail over to the next row, but do not charge the row.
     */
    public static boolean countsAgainstBreaker(Throwable error) {
        if (!isRetryableOnAnotherProvider(error)) {
            return false;
        }
        int status = ((UpstreamProviderException) error).getUpstreamStatus();
        return status != 400 && status != 409;
    }
}
