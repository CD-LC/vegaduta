package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import com.openai.models.completions.CompletionUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage;

/**
 * The cached-vs-uncached split of one LLM call's INPUT tokens, normalized across providers that
 * each report it in a different shape and with a different billing convention.
 *
 * <p><b>Why this exists.</b> Prompt caching is already switched on here — {@link
 * AnthropicProviderCachingStrategy} drives Spring AI 1.1's {@code AnthropicCacheOptions}, {@link
 * OpenAIProviderCachingStrategy} sets the {@code prompt_cache_key} routing hint, and {@link
 * GoogleGenAiCacheManager} drives the real Gemini Caches API — but nothing measured whether any of
 * it was still working. Spring AI's own token counter is two-valued ({@link
 * Usage#getPromptTokens()} / {@link Usage#getCompletionTokens()}, see {@link Usage} in 1.1.7 —
 * there is no cache accessor on the interface at all, verified by reading the pinned {@code
 * spring-ai-model-1.1.7.jar} on this host 2026-09-11), so a prompt edit that collapses the cache
 * hit ratio from 90% to 0% shows up as... nothing. Same call count, same latency band, a quietly
 * larger bill. Cache reads bill at 0.1x base input on Anthropic (see {@link LlmPricingCatalog}'s
 * {@code CACHE_READ_MULTIPLIER}) and — the part that is not a discount but a throughput lever — do
 * not count toward input tokens-per-minute on most Claude models, so losing the prefix costs rate
 * limit as well as money. That regression has to be visible as its own measurement, which means the
 * three input-token buckets have to be carried separately rather than summed into one "prompt
 * tokens" number.
 *
 * <p><b>Absent is not zero.</b> Every count here is a nullable {@link Long} on purpose. {@code
 * null} means "this provider did not report this dimension on this call" — an unknown. {@code 0}
 * means "this provider reported it and it was zero" — a real, and very different, measurement: a
 * total cache miss on a call that was supposed to hit is exactly the regression this class was
 * written to surface, and folding it into the same bucket as "we have no idea" would hide it.
 * {@link #ABSENT} is the explicit nothing-known value; {@link #isPresent()} is the guard every
 * metric emission goes behind, so an unmeasured call never creates a meter series that would read
 * as a measured zero.
 *
 * <p><b>The inclusive/exclusive trap, and why cost is left alone.</b> The three providers disagree
 * about whether their own {@code prompt_tokens} number already contains the cached reads:
 *
 * <ul>
 *   <li><b>Anthropic</b> — EXCLUSIVE. {@code AnthropicApi.Usage.inputTokens()} counts only
 *       freshly-processed input; {@code cacheReadInputTokens()} and {@code
 *       cacheCreationInputTokens()} are disjoint additional buckets. Spring AI maps {@code
 *       promptTokens} straight from {@code inputTokens()} without adding the cache fields in (read
 *       from {@code AnthropicChatModel.getDefaultUsage}'s bytecode in the pinned 1.1.7 jar,
 *       2026-09-11), so {@code promptTokens} IS the uncached bucket and the three add up to total
 *       input.
 *   <li><b>OpenAI</b> — INCLUSIVE. {@code OpenAiApi.Usage.promptTokensDetails().cachedTokens()} is
 *       a breakdown OF {@code promptTokens}, not an addition to it, so the uncached bucket has to
 *       be derived by subtraction. OpenAI also reports no cache-write count at all (its caching is
 *       automatic and writes are not separately billed), so the write bucket stays {@code null} —
 *       absent, not zero.
 *   <li><b>Google GenAI</b> — INCLUSIVE, same as OpenAI. {@code
 *       GoogleGenAiUsage.getCachedContentTokenCount()} is part of the prompt token count, and there
 *       is no separate write count on the response (creating a Gemini cache object is its own API
 *       call — see {@link GoogleGenAiCacheManager} — not a field on a generation response).
 * </ul>
 *
 * <p>That difference is why {@link #billableCacheReadTokens()} / {@link
 * #billableCacheWriteTokens()} exist as separate accessors from the raw ones. {@link
 * LlmPricingCatalog#estimateCostUsd(String, Long, Long, Long, Long)} prices {@code promptTokens} at
 * the base input rate and then ADDS the cache buckets at their own rates — arithmetic that is
 * correct only for a provider whose buckets are disjoint. Feeding an inclusive provider's cached
 * count into it would double-bill those tokens, so the billable accessors deliberately return
 * {@code null} for inclusive providers: the cost estimate keeps behaving exactly as it does today
 * for every provider that previously reported no cache tokens, and the newly available
 * OpenAI/Gemini cache reads are used for observability only. Pricing them properly needs a
 * cost-calculation change (subtract the cached tokens from the base input bucket first), which is a
 * deliberate behaviour change to the billing path and therefore not something to smuggle in under a
 * metrics ticket.
 *
 * <p><b>Known measurement gap: multi-iteration tool turns.</b> For a turn where Spring AI's
 * automatic tool-calling loop ran more than one model round trip, {@code
 * UsageCalculator.getCumulativeUsage} sums the iterations into a plain {@code DefaultUsage(prompt,
 * completion, total)} built with the three-arg constructor — which does not carry a native usage
 * object (verified against the pinned {@code spring-ai-model-1.1.7.jar} bytecode, 2026-09-11). So
 * on those turns {@link Usage#getNativeUsage()} is {@code null}, no provider branch below matches,
 * and this resolves to {@link #ABSENT}. That is the honest outcome — the cache split genuinely is
 * not recoverable from what Spring AI hands back — and it is why the hit ratio must always be read
 * against {@code llm.cache.accounted.calls} rather than against {@code llm.calls}. It is also a
 * pre-existing property of the Anthropic cache columns on {@code llm_call_log}, not something this
 * class introduced.
 *
 * @param cacheReadInputTokens input tokens served from a warm prompt cache, or {@code null} if this
 *     provider did not report the dimension.
 * @param cacheWriteInputTokens input tokens written into the cache on this call (billed at a
 *     premium where the provider charges for writes at all), or {@code null} if not reported —
 *     which is the normal case for every provider except Anthropic.
 * @param uncachedInputTokens input tokens the provider actually processed fresh. Equal to {@code
 *     promptTokens} for an exclusive provider, and {@code promptTokens - cacheReadInputTokens} for
 *     an inclusive one. {@code null} when {@code promptTokens} itself was unavailable.
 * @param cacheReadsIncludedInPromptTokens which billing convention the source provider uses — see
 *     the inclusive/exclusive discussion above. Meaningless (and {@code false}) on {@link #ABSENT}.
 */
public record CacheTokenAccounting(
        Long cacheReadInputTokens,
        Long cacheWriteInputTokens,
        Long uncachedInputTokens,
        boolean cacheReadsIncludedInPromptTokens) {

    /**
     * "Nothing is known about this call's cache behaviour" — a failed call, a response with no
     * usage metadata, a provider this platform supports that reports no cache dimension (Bedrock
     * via the hand-rolled gateway, Mistral, Sarvam), or a multi-iteration tool turn whose native
     * usage Spring AI dropped (see the class javadoc). Distinct in every way from a call that
     * reported a zero cache hit.
     */
    public static final CacheTokenAccounting ABSENT =
            new CacheTokenAccounting(null, null, null, false);

    /**
     * Normalizes whatever cache dimensions {@code usage} actually carries into this shape,
     * returning {@link #ABSENT} when it carries none.
     *
     * <p>Provider detection is by the concrete type Spring AI 1.1.7 puts in front of us, which
     * differs per provider and is why this is a chain of {@code instanceof} branches rather than
     * one accessor call: Anthropic and OpenAI both hang their own record off {@link
     * Usage#getNativeUsage()}, while {@code GoogleGenAiUsage} IS the {@code Usage} implementation
     * (it extends {@code DefaultUsage} and exposes the Gemini-specific counts as its own getters).
     * All three types are on core/chat's compile classpath already — the module depends on all
     * three starters, see its pom.xml.
     *
     * <p>Never throws: a malformed or partially-populated usage payload degrades to {@link #ABSENT}
     * or to a partially-populated accounting rather than propagating anything into the chat turn,
     * matching this codebase's standing "a missing metric never breaks the real feature" rule (see
     * {@link LlmCallLogService#record}).
     */
    public static CacheTokenAccounting from(Usage usage) {
        return from(usage, null);
    }

    /**
     * Same as {@link #from(Usage)}, with the provider that produced the usage. Spring AI 2 sums
     * usage across the iterations of a tool turn in a plain {@code DefaultUsage} that carries the
     * token counts and the cache read/write counts but NOT the provider-native usage object, so on
     * a tool turn the billing convention (exclusive Anthropic vs inclusive OpenAI/Gemini) can no
     * longer be recovered from the usage itself; the caller, which knows the provider, passes it
     * here.
     */
    public static CacheTokenAccounting from(Usage usage, ProviderName provider) {
        if (usage == null) {
            return ABSENT;
        }
        Long promptTokens =
                usage.getPromptTokens() == null ? null : usage.getPromptTokens().longValue();

        // Gemini first: GoogleGenAiUsage is itself the Usage implementation,
        // so checking it before touching getNativeUsage() avoids depending
        // on what that (Gemini-internal) object happens to be.
        if (usage instanceof GoogleGenAiUsage googleUsage) {
            Long cachedContent =
                    googleUsage.getCachedContentTokenCount() == null
                            ? null
                            : googleUsage.getCachedContentTokenCount().longValue();
            if (cachedContent == null) {
                return ABSENT;
            }
            return inclusive(cachedContent, promptTokens);
        }

        // Spring AI 2 reports the cache split on the Usage interface itself (null = not reported),
        // for a
        // single call AND summed across a tool loop. The native SDK object is only the fallback.
        Long cacheRead = usage.getCacheReadInputTokens();
        Long cacheWrite = usage.getCacheWriteInputTokens();
        Object nativeUsage = usage.getNativeUsage();
        boolean exclusive =
                provider == ProviderName.ANTHROPIC
                        || nativeUsage instanceof com.anthropic.models.messages.Usage;
        boolean inclusive =
                provider != null && provider != ProviderName.ANTHROPIC
                        || nativeUsage instanceof CompletionUsage;
        if (nativeUsage instanceof com.anthropic.models.messages.Usage anthropicUsage) {
            if (cacheRead == null) {
                cacheRead = anthropicUsage.cacheReadInputTokens().orElse(null);
            }
            if (cacheWrite == null) {
                cacheWrite = anthropicUsage.cacheCreationInputTokens().orElse(null);
            }
        } else if (nativeUsage instanceof CompletionUsage openAiUsage && cacheRead == null) {
            cacheRead =
                    openAiUsage.promptTokensDetails().flatMap(d -> d.cachedTokens()).orElse(null);
        }
        if (exclusive) {
            if (cacheRead == null && cacheWrite == null) {
                // Anthropic omits both fields entirely on a request that
                // carried no cache_control block at all - genuinely nothing
                // measured, as opposed to a measured miss (which comes back
                // as an explicit 0).
                return ABSENT;
            }
            // Exclusive convention: inputTokens (which Spring AI surfaced as
            // promptTokens) already excludes both cache buckets, so it IS
            // the uncached count - no subtraction, and both buckets are
            // billable as their own line items.
            return new CacheTokenAccounting(cacheRead, cacheWrite, promptTokens, false);
        }
        if (inclusive) {
            if (cacheRead == null) {
                return ABSENT;
            }
            return inclusive(cacheRead, promptTokens);
        }
        return ABSENT;
    }

    /**
     * Builds the accounting for an INCLUSIVE provider (OpenAI, Google GenAI): the cached count is a
     * breakdown of {@code promptTokens}, so the uncached bucket is the remainder. Clamped at zero
     * defensively — a provider reporting more cached tokens than prompt tokens would be a provider
     * bug, and a negative "uncached" count would poison a Micrometer counter permanently, so it
     * degrades to 0 rather than propagating. The write bucket is {@code null} because neither
     * provider reports one (see the class javadoc).
     */
    private static CacheTokenAccounting inclusive(Long cachedTokens, Long promptTokens) {
        Long uncached = promptTokens == null ? null : Math.max(0L, promptTokens - cachedTokens);
        return new CacheTokenAccounting(cachedTokens, null, uncached, true);
    }

    /**
     * Whether this call reported ANY cache dimension, i.e. whether the buckets below are a
     * measurement rather than an unknown. Every metric emission and span attribute is gated on this
     * so an unmeasured call never produces a series that reads as a measured zero.
     */
    public boolean isPresent() {
        return cacheReadInputTokens != null || cacheWriteInputTokens != null;
    }

    /**
     * Total input tokens this call is accounted for across all three buckets, or {@code null} when
     * nothing was measured. Note this can legitimately differ from {@code promptTokens} for an
     * exclusive provider (where it is larger, because {@code promptTokens} is only the uncached
     * slice) and equals it for an inclusive one.
     */
    public Long totalInputTokens() {
        if (!isPresent()) {
            return null;
        }
        return nullToZero(cacheReadInputTokens)
                + nullToZero(cacheWriteInputTokens)
                + nullToZero(uncachedInputTokens);
    }

    /**
     * Cache reads as a fraction of this call's total accounted input tokens, in {@code [0, 1]} —
     * the single number a prompt change collapsing the shared prefix moves, and the reason this
     * class exists.
     *
     * <p>{@code null} (not {@code 0.0}) in two cases, both of which are "undefined", not "no hits":
     * when no cache read count was reported at all, and when the total accounted input is zero so
     * the ratio would be 0/0. A genuine total miss on a measured call is {@code 0.0} and is meant
     * to be loud.
     */
    public Double hitRatio() {
        if (cacheReadInputTokens == null) {
            return null;
        }
        long total =
                nullToZero(cacheReadInputTokens)
                        + nullToZero(cacheWriteInputTokens)
                        + nullToZero(uncachedInputTokens);
        if (total <= 0) {
            return null;
        }
        return (double) cacheReadInputTokens / (double) total;
    }

    /**
     * The cache-read count safe to hand to {@link LlmPricingCatalog#estimateCostUsd(String, Long,
     * Long, Long, Long)} as its own billable line item — i.e. the raw count for an exclusive
     * provider, and {@code null} for an inclusive one whose tokens are already inside the base
     * {@code promptTokens} bucket that catalog prices. See the class javadoc's cost discussion for
     * why this is deliberately lossy rather than "better".
     */
    public Long billableCacheReadTokens() {
        return cacheReadsIncludedInPromptTokens ? null : cacheReadInputTokens;
    }

    /** Cache-write counterpart to {@link #billableCacheReadTokens()} — same reasoning. */
    public Long billableCacheWriteTokens() {
        return cacheReadsIncludedInPromptTokens ? null : cacheWriteInputTokens;
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }
}
