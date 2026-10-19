package com.agenticai.core.agents.turn;

import com.agenticai.core.chat.UpstreamProviderException;
import java.util.Locale;

/**
 * The one place that decides "is this failure worth trying the NEXT hosted provider for, or is
 * switching providers guaranteed not to help?" - used by {@code
 * AgentChatService#attemptHostedChain}, and (2026-08-05) by BOTH of {@code AgentChatService}'s BYOK
 * fallback sites ({@code streamFast}'s fallback-provider branch and {@code
 * callWithProviderFallback}), which previously fell back on ANY upstream error: the same one-table
 * decision now gates hosted-pool advancement and BYOK fallback alike, so a content-policy refusal
 * or an over-length prompt is never replayed against a second provider anywhere, while an
 * invalid-model 400/404 still is.
 *
 * <p>Before this existed the chain advanced on ANY {@link UpstreamProviderException}, which is too
 * broad in one direction (a content-policy refusal or an over-length prompt gets refused
 * identically by every provider, so re-sending it N times just multiplies latency and spend before
 * showing the user the same message) - and, in the live production symptom this class was written
 * for, the chain never ran at all because the pool had a single row. Classification lives here
 * rather than next to {@code ProviderErrorTranslator} in {@code core/chat} deliberately: this is a
 * ROUTING decision owned by the pool, not part of error surfacing, and keeping it in {@code
 * core/agents} avoids editing a file that is under concurrent change.
 *
 * <h4>Classification</h4>
 *
 * <ul>
 *   <li><b>Never retryable</b> - anything that is a property of the USER'S INPUT rather than of the
 *       provider: content-policy / moderation refusals, context-length / payload-too-large, and
 *       semantic rejections of the request as sent (413/422). Every provider in the pool would
 *       reject the same input the same way, and the consumer message {@code
 *       ProviderErrorTranslator#classify} already produced for these is the actionable one
 *       ("shorten the message", "try rephrasing it"), so it should reach the user immediately
 *       instead of after N pointless round-trips.
 *   <li><b>Retryable</b> - anything that is a property of THIS PROVIDER: 401 / 403 (its key), 402
 *       (its billing), 404 (its model id or endpoint shape - the exact live symptom: a Gemini row
 *       whose model Google rejects), 408 / 429 (its capacity), and every 5xx / timeout / connection
 *       error. Each pool row carries its own key, endpoint and model, so the next row genuinely can
 *       succeed where this one cannot.
 *   <li><b>400</b> is treated as retryable. In practice an OpenAI-compatible 400 from these hosts
 *       is overwhelmingly "this provider doesn't accept that model / parameter", which the next row
 *       may well accept; the genuinely input-shaped 400s (content policy, context length) are
 *       already caught by the keyword pass above it. Worst case this costs one extra attempt per
 *       remaining row, bounded by the chain length.
 * </ul>
 *
 * <p>Non-{@link UpstreamProviderException} failures are never retryable at all - guardrail
 * violations, tool step limits and moderation blocks did not come from a provider, so switching
 * providers cannot fix them (the same exclusion {@code streamFast}'s {@code onErrorMap} and {@code
 * callWithProviderFallback} already apply on the BYOK side).
 */
final class HostedFailoverPolicy {

    /**
     * Phrases every major provider (OpenAI / Anthropic / Gemini / OpenRouter / Groq) actually uses
     * for the two "the user's own input is the problem" families. Deliberately narrow, same posture
     * as {@code ProviderErrorTranslator#classify}'s keyword sniffing: an unrecognised detail falls
     * through to the status-based rule rather than being guessed at.
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

    private HostedFailoverPolicy() {}

    /**
     * @return true when the NEXT provider in the hosted chain deserves a chance at this turn. See
     *     this class's javadoc for the full classification and why each bucket falls where it does.
     */
    static boolean isRetryable(Throwable error) {
        if (!(error instanceof UpstreamProviderException upstream)) {
            return false;
        }
        // getMessage(), not getConsumerMessage(): the technical one still
        // carries the provider's own extracted reason ("model ... not found",
        // "content_policy_violation", ...), which is exactly what this needs -
        // the consumer message has deliberately had that detail stripped out.
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
            // Everything else: server-side upstream failures (incl. the 502
            // ProviderErrorTranslator falls back to for connection errors and
            // unstructured exceptions) are retryable; unknown 2xx/3xx shapes,
            // which should never reach here, are not.
            default -> status >= 500;
        };
    }

    /**
     * Whether a failure should count toward the row's CIRCUIT BREAKER. Narrower than {@link
     * #isRetryable}: the breaker is shared by every tenant on the pooled row, and a retryable
     * 400/409 is usually the caller's request shape (tool schema, replayed history, unsupported
     * parameter), which one tenant can repeat until the row is skipped for everyone. Those still
     * fail over to the next row, but do not charge the row. 401/402/403/404 (the row's own key,
     * billing, model id) and 408/425/429 (the row's capacity - this is a shared platform pool, so a
     * 429 is the pool's own limit) and 5xx/timeouts/ connection errors count.
     */
    static boolean countsAgainstBreaker(Throwable error) {
        if (!isRetryable(error)) {
            return false;
        }
        int status = ((UpstreamProviderException) error).getUpstreamStatus();
        return status != 400 && status != 409;
    }
}
