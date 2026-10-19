package com.agenticai.core.chat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Hand-maintained $/million-token rates for every model family this platform
 * can route to - Anthropic Claude (sourced from Anthropic's official pricing
 * page, verified 2026-07-10) plus the Gemini / OpenAI / Groq-served open-model
 * / DeepSeek / Mistral families the platform-hosted pool and BYOK agents use
 * (cross-provider list-price estimates, see the table below). Extended beyond
 * Claude-only because those calls previously recorded a NULL/$0 cost, leaving
 * the "incurred cost" total and any per-user dollar budget blind.
 *
 * <p><b>This produces an ESTIMATE, not a bill.</b> It multiplies plain
 * prompt/completion token counts by base (non-cached) input/output rates,
 * plus - when the caller supplies them, see {@link
 * #estimateCostUsd(String, Long, Long, Long, Long)} - Anthropic prompt-cache
 * write/read token counts at their own distinct rates (see {@code
 * Rate#cacheWritePerMillion}/{@code cacheReadPerMillion} below). It does NOT
 * account for tool-use system-prompt overhead or the Sonnet 5
 * introductory-to-standard price step on 2026-09-01 (see {@code
 * SONNET_5_STANDARD_PRICING_EFFECTIVE} below - this class does switch rates
 * automatically at that date, but every other simplification above still
 * applies). The "~30% extra tokens the newer tokenizer produces" caveat that
 * used to live here was checked (2026-08-08) and is not a real gap for this
 * class: it only multiplies token counts the provider's API already reported
 * as actually used, not a client-side text-length estimate, so whatever
 * tokenizer produced those counts is already reflected in them - there is no
 * separate overhead for this class to add. Good for relative comparison and
 * a directional running total, not a substitute for the tenant's real
 * Anthropic Console billing page - the UI surfacing this number says so
 * explicitly rather than presenting it as authoritative.
 *
 * <p>Matched by prefix (not exact string) against whatever model id an
 * agent is configured with, so a dated suffix (a specific snapshot id) still
 * resolves to its family's rate. Unrecognized models fall back to {@link
 * #defaultUnknownModelRate()} - a fixed conservative mid-range rate - rather
 * than returning {@code null}, so a model missing from
 * this catalog can never silently bypass per-user/per-tenant budget
 * enforcement just because nobody has priced it yet; the hit is counted and
 * a (rate-limited) warning logged so the gap gets noticed and the real rate
 * added - see "fallback observability" below. {@code null} is still returned when the call itself has no usable
 * usage data (missing model id or token counts) - that is "nothing to
 * price," not "unpriced model."
 *
 * <p><b>Fallback observability (2026-09-28).</b> The tier defaults
 * {@code AgentService} hands new agents ({@code gpt-5.1},
 * {@code gemini-3.1-flash-lite}, {@code gemini-3.5-flash}) have no row in the
 * table below, so every call on them is priced at the fallback rate. No
 * verified list price for those ids exists anywhere in this repo (docs/ and
 * web/ were searched 2026-09-28), and inventing one would put a made-up number
 * on every tenant's cost page and budget - so they are deliberately NOT added
 * here. Instead the gap is made measurable: every fallback-priced estimate
 * increments the Micrometer counter {@code llm.pricing.missing} (tags
 * {@code provider}, {@code model}), and the WARN that used to fire on EVERY
 * such call - an identical line per call on a busy tier default - is
 * rate-limited to once per model per {@link #FALLBACK_WARN_INTERVAL}. Both the
 * tag set and the warn bookkeeping are bounded by {@link
 * #MAX_TRACKED_UNPRICED_MODELS} distinct models (overflow collapses into the
 * tag value {@code other}), because the model id is caller-supplied and an
 * unbounded tag is a Prometheus cardinality leak. The counter counts
 * fallback-priced ESTIMATES, not calls: one chat turn can be estimated more
 * than once (the advisor's live metric and the durable {@code llm_call_log}
 * row each price it), so read it as "is this non-zero / which models", not as
 * a call count.
 */
@Component
public class LlmPricingCatalog {

    private static final Logger log = LoggerFactory.getLogger(LlmPricingCatalog.class);

    /** Micrometer counter incremented every time an estimate falls back to {@link #defaultUnknownModelRate()}. */
    public static final String PRICING_MISSING_METRIC = "llm.pricing.missing";
    /** {@code provider} tag value for call sites that price by model id alone. */
    static final String UNKNOWN_PROVIDER_TAG = "unknown";
    /** {@code model} tag value once {@link #MAX_TRACKED_UNPRICED_MODELS} distinct models are already tracked. */
    static final String OVERFLOW_MODEL_TAG = "other";
    static final int MAX_TRACKED_UNPRICED_MODELS = 50;
    private static final int MAX_MODEL_TAG_LENGTH = 100;
    static final Duration FALLBACK_WARN_INTERVAL = Duration.ofMinutes(10);

    /** Null when no registry is available (plain {@code new LlmPricingCatalog()} in tests) - metering is then skipped, pricing is unaffected. */
    private final MeterRegistry meterRegistry;
    private final LongSupplier nanoClock;
    /** model tag -> nanoClock reading of its last fallback WARN. Bounded, see {@link #trackedModelTag}. */
    private final Map<String, Long> lastFallbackWarnNanosByModel = new ConcurrentHashMap<>();

    /** Unmetered catalog - the shape every existing {@code new LlmPricingCatalog()} test fixture uses. */
    public LlmPricingCatalog() {
        this((MeterRegistry) null, System::nanoTime);
    }

    /**
     * Spring constructor. {@link ObjectProvider} rather than a hard {@link
     * MeterRegistry} dependency so a context without Micrometer still boots
     * this bean - pricing never depends on metrics being available.
     */
    @Autowired
    public LlmPricingCatalog(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable(), System::nanoTime);
    }

    /** Test seam - injectable registry and monotonic clock for the WARN rate limit. */
    LlmPricingCatalog(MeterRegistry meterRegistry, LongSupplier nanoClock) {
        this.meterRegistry = meterRegistry;
        this.nanoClock = nanoClock;
    }

    /**
     * input $/MTok, output $/MTok, plus (Anthropic only) the prompt-cache
     * write/read $/MTok rates - {@code null} for either cache field means
     * "no cache-specific pricing known for this model family," which {@link
     * #estimateCostUsd(String, Long, Long, Long, Long)} treats as billing
     * those cache tokens at the plain {@code inputPerMillion} rate rather
     * than silently dropping them from the estimate.
     */
    private record Rate(
        BigDecimal inputPerMillion, BigDecimal outputPerMillion,
        BigDecimal cacheWritePerMillion, BigDecimal cacheReadPerMillion
    ) {
        // Anthropic's published prompt-caching multipliers off the base
        // input rate for the default 5-minute ephemeral TTL - the only TTL
        // AnthropicAgentChatClientFactory's AnthropicCacheOptions currently
        // issues (no ttl("1h") override wired anywhere in this codebase). If
        // a 1-hour-TTL cache breakpoint is ever added there, its writes bill
        // at 2x input (not 1.25x) and this multiplier would need to become
        // per-call rather than per-model-family.
        private static final BigDecimal CACHE_WRITE_MULTIPLIER_5MIN_TTL = bd("1.25");
        private static final BigDecimal CACHE_READ_MULTIPLIER = bd("0.1");

        /** Non-Anthropic model, or an Anthropic family with no confirmed cache rate yet - no cache-specific pricing. */
        Rate(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
            this(inputPerMillion, outputPerMillion, null, null);
        }

        /** Anthropic model: derives cache write/read rates from the base input rate per Anthropic's published cache pricing. */
        static Rate anthropic(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
            return new Rate(
                inputPerMillion, outputPerMillion,
                inputPerMillion.multiply(CACHE_WRITE_MULTIPLIER_5MIN_TTL),
                inputPerMillion.multiply(CACHE_READ_MULTIPLIER)
            );
        }
    }

    // Anthropic's posted "Claude Sonnet 5" row is time-boxed introductory
    // pricing through 2026-08-31, stepping up 2026-09-01 - the one rate in
    // this table known in advance to change on a fixed date rather than
    // needing a manual edit when Anthropic next revises pricing.
    private static final Instant SONNET_5_STANDARD_PRICING_EFFECTIVE =
        Instant.parse("2026-09-01T00:00:00Z");

    private static final Rate SONNET_5_INTRODUCTORY = Rate.anthropic(bd("2"), bd("10"));
    private static final Rate SONNET_5_STANDARD = Rate.anthropic(bd("3"), bd("15"));

    /**
     * Genuinely zero-cost models. OpenRouter publishes free variants of hosted
     * models under a {@code ":free"} suffix (20 of 444 catalog entries on
     * 2026-09-17, e.g. {@code nex-agi/nex-n2.5-mini:free}); the platform's
     * free tier is expected to point at one.
     *
     * <p>Without this, such a model matches no prefix in {@link
     * #RATES_BY_MODEL_PREFIX} and falls through to {@link
     * #defaultUnknownModelRate()} - metering traffic that costs nothing at
     * $0.50/$2.00 per MTok. That is not just a wrong number on a dashboard: the
     * same figure debits per-user budgets and drives budget enforcement, so a
     * free-tier user would be cut off for spending money nobody spent.
     */
    private static final Rate FREE = new Rate(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    // Ordered longest-prefix-first isn't required here (each model family's
    // prefixes are already mutually exclusive strings), but insertion order
    // is kept deliberately grouped by family for readability when this
    // table needs a manual update.
    private static final Map<String, Rate> RATES_BY_MODEL_PREFIX = new LinkedHashMap<>();

    static {
        RATES_BY_MODEL_PREFIX.put("claude-opus-4-8", Rate.anthropic(bd("5"), bd("25")));
        RATES_BY_MODEL_PREFIX.put("claude-opus-4-7", Rate.anthropic(bd("5"), bd("25")));
        RATES_BY_MODEL_PREFIX.put("claude-opus-4-6", Rate.anthropic(bd("5"), bd("25")));
        RATES_BY_MODEL_PREFIX.put("claude-opus-4-5", Rate.anthropic(bd("5"), bd("25")));
        RATES_BY_MODEL_PREFIX.put("claude-opus-4-1", Rate.anthropic(bd("15"), bd("75")));
        RATES_BY_MODEL_PREFIX.put("claude-opus-4", Rate.anthropic(bd("15"), bd("75")));
        // claude-sonnet-5 resolved dynamically below (introductory vs standard).
        RATES_BY_MODEL_PREFIX.put("claude-sonnet-4-6", Rate.anthropic(bd("3"), bd("15")));
        RATES_BY_MODEL_PREFIX.put("claude-sonnet-4-5", Rate.anthropic(bd("3"), bd("15")));
        RATES_BY_MODEL_PREFIX.put("claude-sonnet-4", Rate.anthropic(bd("3"), bd("15")));
        RATES_BY_MODEL_PREFIX.put("claude-haiku-4-5", Rate.anthropic(bd("1"), bd("5")));
        RATES_BY_MODEL_PREFIX.put("claude-haiku-3-5", Rate.anthropic(bd("0.80"), bd("4")));

        // Non-Anthropic rates (added for the platform-hosted pool + BYOK cost
        // tracking - without these, every non-Claude call recorded a NULL/$0
        // cost, so the "incurred cost" total and any per-user dollar budget were
        // blind). These are cross-provider list-price ESTIMATES ($/MTok in/out,
        // verified ~2026-07): the SAME open model can be served by several hosts
        // (Groq/Together/Fireworks/OpenRouter) at different prices, and this
        // catalog matches by model-id prefix only (provider-agnostic), so treat
        // these as directional - the same "estimate, not a bill" caveat in this
        // class's header applies with a bit more slack for open models.
        //
        // IMPORTANT: prefixes are matched first-wins in insertion order, and
        // some of these ARE overlapping (flash-lite starts with flash;
        // 4o-mini starts with 4o) - so the LONGER/cheaper variant MUST be
        // inserted BEFORE its shorter sibling or it would inherit the wrong rate.
        RATES_BY_MODEL_PREFIX.put("gemini-2.0-flash-lite", new Rate(bd("0.075"), bd("0.30")));
        RATES_BY_MODEL_PREFIX.put("gemini-2.5-flash-lite", new Rate(bd("0.10"), bd("0.40")));
        RATES_BY_MODEL_PREFIX.put("gemini-2.5-flash", new Rate(bd("0.30"), bd("2.50")));
        RATES_BY_MODEL_PREFIX.put("gemini-2.0-flash", new Rate(bd("0.10"), bd("0.40")));
        RATES_BY_MODEL_PREFIX.put("gemini-1.5-flash", new Rate(bd("0.075"), bd("0.30")));
        RATES_BY_MODEL_PREFIX.put("gemini-2.5-pro", new Rate(bd("1.25"), bd("10")));
        RATES_BY_MODEL_PREFIX.put("gemini-1.5-pro", new Rate(bd("1.25"), bd("5")));

        RATES_BY_MODEL_PREFIX.put("gpt-4o-mini", new Rate(bd("0.15"), bd("0.60")));
        RATES_BY_MODEL_PREFIX.put("gpt-4o", new Rate(bd("2.50"), bd("10")));
        RATES_BY_MODEL_PREFIX.put("gpt-4.1-mini", new Rate(bd("0.40"), bd("1.60")));
        RATES_BY_MODEL_PREFIX.put("gpt-4.1-nano", new Rate(bd("0.10"), bd("0.40")));
        RATES_BY_MODEL_PREFIX.put("gpt-4.1", new Rate(bd("2"), bd("8")));
        // AZURE OPENAI DEPLOYMENT NAMES USE DASHES, NOT DOTS — and the miss was
        // not cosmetic, it mis-ordered the whole hosted pool (2026-09-12).
        //
        // Azure OpenAI addresses a model by DEPLOYMENT name, and a deployment
        // name cannot contain "."; the production hosted-pool row is deployed as
        // `gpt-4-1-mini`. resolveRate lowercases and prefix-matches, so
        // `gpt-4-1-mini` matched NOTHING above and fell through to
        // defaultUnknownModelRate() — at the time of that incident deliberately
        // the HIGHEST rate in this table (since softened to a fixed mid-range
        // fallback, see defaultUnknownModelRate's javadoc — still far above a
        // mini's real rate, so the sort-order bug below reads the same either way).
        //
        // That fed straight into FreeTierChatClientService#orderForFailover,
        // which sorts the pool CHEAPEST-FIRST (app.hosted-pool.order-by-cost
        // defaults true). The live Azure provider therefore priced as the most
        // expensive row in the pool and sorted LAST, while a stale Vertex
        // us-central1 row priced correctly on `gemini-2.5-flash` and sorted
        // FIRST — so turns were attempting a dead US provider before the live
        // one, paying an India->us-central1 round trip on the way. The circuit
        // breaker could not rescue it either: HostedProviderHealth resets the
        // failure streak after a 120s window, so on a quiet FREE tier the
        // 3-failure threshold is never reached.
        //
        // Keep BOTH spellings. The dotted key is the vendor's own model id (used
        // by OpenAI direct and by the model catalog); the dashed one is what
        // Azure deployments actually report. Any future Azure deployment of a
        // dotted model needs its dashed twin added here in the same commit.
        RATES_BY_MODEL_PREFIX.put("gpt-4-1-mini", new Rate(bd("0.40"), bd("1.60")));
        RATES_BY_MODEL_PREFIX.put("gpt-4-1-nano", new Rate(bd("0.10"), bd("0.40")));
        RATES_BY_MODEL_PREFIX.put("gpt-4-1", new Rate(bd("2"), bd("8")));

        // Groq-served open models (the platform pool's fast default family).
        RATES_BY_MODEL_PREFIX.put("llama-3.3-70b", new Rate(bd("0.59"), bd("0.79")));
        RATES_BY_MODEL_PREFIX.put("llama-3.1-8b", new Rate(bd("0.05"), bd("0.08")));
        RATES_BY_MODEL_PREFIX.put("llama-3.1-70b", new Rate(bd("0.59"), bd("0.79")));
        RATES_BY_MODEL_PREFIX.put("gemma-3-27b", new Rate(bd("0.10"), bd("0.10")));
        RATES_BY_MODEL_PREFIX.put("gemma-2-9b", new Rate(bd("0.05"), bd("0.05")));

        // DeepSeek (BYOK) - standard, non-cache list price.
        RATES_BY_MODEL_PREFIX.put("deepseek-chat", new Rate(bd("0.27"), bd("1.10")));
        RATES_BY_MODEL_PREFIX.put("deepseek-reasoner", new Rate(bd("0.55"), bd("2.19")));

        // Mistral (BYOK).
        RATES_BY_MODEL_PREFIX.put("mistral-small", new Rate(bd("0.10"), bd("0.30")));
        RATES_BY_MODEL_PREFIX.put("mistral-large", new Rate(bd("2"), bd("6")));

        // Meta Model API (BYOK, api.meta.ai) - Muse Spark, per dev.meta.ai on
        // 2026-09-17: standard $1.25 in / $4.25 out, cached input $0.15 (no
        // separate cache-write charge is published, so cache writes bill at
        // the plain input rate via the null cacheWritePerMillion fallback);
        // "-contributor" ids are $0.10 / $0.20 because Meta trains on that
        // traffic. resolveRate() is FIRST prefix match in insertion order, so
        // the longer "-contributor" keys MUST be inserted before the bare
        // family keys they extend - otherwise "muse-spark-1.3-contributor"
        // would match "muse-spark-1.3" and be billed at 12x its real rate.
        // Through OpenRouter the same models are spelled "meta/muse-spark-1.3"
        // etc. and LlmMetricsAdvisor hands this table the raw id for every
        // provider (there is no OpenRouter cost-metadata path), so the
        // vendor-prefixed spellings get their own rows - OpenRouter lists
        // them at Meta's list price, so the numbers are identical.
        RATES_BY_MODEL_PREFIX.put("muse-spark-1.3-contributor", new Rate(bd("0.10"), bd("0.20")));
        RATES_BY_MODEL_PREFIX.put("muse-spark-1.2-contributor", new Rate(bd("0.10"), bd("0.20")));
        RATES_BY_MODEL_PREFIX.put("muse-spark-1.3", new Rate(bd("1.25"), bd("4.25"), null, bd("0.15")));
        RATES_BY_MODEL_PREFIX.put("muse-spark-1.2", new Rate(bd("1.25"), bd("4.25"), null, bd("0.15")));
        RATES_BY_MODEL_PREFIX.put("muse-spark-1.1", new Rate(bd("1.25"), bd("4.25"), null, bd("0.15")));
        RATES_BY_MODEL_PREFIX.put("meta/muse-spark-1.3-contributor", new Rate(bd("0.10"), bd("0.20")));
        RATES_BY_MODEL_PREFIX.put("meta/muse-spark-1.2-contributor", new Rate(bd("0.10"), bd("0.20")));
        RATES_BY_MODEL_PREFIX.put("meta/muse-spark", new Rate(bd("1.25"), bd("4.25"), null, bd("0.15")));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /**
     * @return the estimated USD cost of this call (input + output tokens
     *     only - see {@link #estimateCostUsd(String, Long, Long, Long,
     *     Long)} to also price Anthropic prompt-cache write/read tokens), or
     *     {@code null} if the model id or either token count is missing
     *     (some providers/error paths never return usage at all - there is
     *     nothing to price). A model id that IS present but not recognized
     *     by this catalog no longer returns {@code null} - see {@link
     *     #defaultUnknownModelRate()}.
     */
    public BigDecimal estimateCostUsd(String model, Long promptTokens, Long completionTokens) {
        return estimateCostUsd(model, promptTokens, completionTokens, null, null);
    }

    /**
     * Same as {@link #estimateCostUsd(String, Long, Long)}, plus - when the
     * caller has them - Anthropic prompt-cache write/read token counts
     * (Spring AI 1.1.7's {@code AnthropicApi.Usage} record exposes {@code
     * cacheCreationInputTokens()}/{@code cacheReadInputTokens()} via {@code
     * ChatResponse.getMetadata().getUsage().getNativeUsage()}; confirmed
     * against the jar on this host 2026-08-08). These are billed at their
     * own per-model cache rates (see {@code Rate#cacheWritePerMillion}/
     * {@code cacheReadPerMillion}) rather than folded into the base input
     * rate, since Anthropic prices them differently (cheaper for reads,
     * a premium for writes). Either count may be {@code null} or {@code 0}
     * for a call that didn't touch the cache - both are treated as "no
     * cache tokens to price," not an error. For a model whose family has no
     * confirmed cache rate (every current non-Anthropic entry), any nonzero
     * cache token counts still get priced, at the plain base input rate,
     * rather than silently dropped from the estimate.
     *
     * @return the estimated USD cost of this call, or {@code null} if the
     *     model id or either base token count is missing.
     */
    public BigDecimal estimateCostUsd(
        String model, Long promptTokens, Long completionTokens,
        Long cacheCreationTokens, Long cacheReadTokens
    ) {
        return estimateCostUsd(null, model, promptTokens, completionTokens, cacheCreationTokens, cacheReadTokens);
    }

    /**
     * Same as {@link #estimateCostUsd(String, Long, Long, Long, Long)};
     * {@code provider} (any stable provider label, e.g. {@code
     * ProviderName.name()}) is used ONLY to tag the {@code llm.pricing.missing}
     * counter when this estimate falls back - it never changes which rate is
     * chosen (the table is provider-agnostic by design, see the non-Anthropic
     * rates comment). {@code null} tags as {@code unknown}.
     */
    public BigDecimal estimateCostUsd(
        String provider, String model, Long promptTokens, Long completionTokens,
        Long cacheCreationTokens, Long cacheReadTokens
    ) {
        return estimateCostUsd(provider, model, promptTokens, completionTokens, cacheCreationTokens, cacheReadTokens, null);
    }

    /**
     * Same as the six-argument overload, plus {@code reasoningTokens}: thinking
     * tokens the provider reports OUTSIDE {@code completionTokens} (Google
     * GenAI {@code thoughtsTokenCount}) and bills at the output rate - see
     * {@link ReasoningTokenAccounting#billableReasoningTokens()}. Pass
     * {@code null} for providers whose completion count already includes them.
     */
    public BigDecimal estimateCostUsd(
        String provider, String model, Long promptTokens, Long completionTokens,
        Long cacheCreationTokens, Long cacheReadTokens, Long reasoningTokens
    ) {
        if (model == null || promptTokens == null || completionTokens == null) {
            return null;
        }
        String normalizedModel = model.toLowerCase(Locale.ROOT);
        Rate rate = resolveRate(normalizedModel);
        if (rate == null) {
            rate = defaultUnknownModelRate();
            recordFallback(provider, normalizedModel, rate);
        }
        BigDecimal cost = costFor(promptTokens, rate.inputPerMillion())
            .add(costFor(completionTokens, rate.outputPerMillion()));
        if (reasoningTokens != null && reasoningTokens > 0) {
            cost = cost.add(costFor(reasoningTokens, rate.outputPerMillion()));
        }
        BigDecimal cacheWriteRate = rate.cacheWritePerMillion() != null ? rate.cacheWritePerMillion() : rate.inputPerMillion();
        BigDecimal cacheReadRate = rate.cacheReadPerMillion() != null ? rate.cacheReadPerMillion() : rate.inputPerMillion();
        if (cacheCreationTokens != null && cacheCreationTokens > 0) {
            cost = cost.add(costFor(cacheCreationTokens, cacheWriteRate));
        }
        if (cacheReadTokens != null && cacheReadTokens > 0) {
            cost = cost.add(costFor(cacheReadTokens, cacheReadRate));
        }
        return cost;
    }

    /**
     * Meters and (rate-limited) logs one fallback-priced estimate - see the
     * class javadoc's "fallback observability" section. Never throws: a
     * metrics failure must not turn a priced call into an unpriced one.
     */
    private void recordFallback(String provider, String normalizedModel, Rate rate) {
        String modelTag = trackedModelTag(normalizedModel);
        String providerTag = provider == null || provider.isBlank()
            ? UNKNOWN_PROVIDER_TAG : provider.toLowerCase(Locale.ROOT);
        try {
            if (meterRegistry != null) {
                meterRegistry.counter(PRICING_MISSING_METRIC, Tags.of("provider", providerTag, "model", modelTag))
                    .increment();
            }
        } catch (RuntimeException e) {
            log.debug("Could not record {} for model {}", PRICING_MISSING_METRIC, modelTag, e);
        }
        long now = nanoClock.getAsLong();
        long intervalNanos = FALLBACK_WARN_INTERVAL.toNanos();
        boolean[] shouldWarn = {false};
        lastFallbackWarnNanosByModel.compute(modelTag, (key, last) -> {
            if (last == null || now - last >= intervalNanos) {
                shouldWarn[0] = true;
                return now;
            }
            return last;
        });
        if (shouldWarn[0]) {
            log.warn(
                "No pricing entry for model \"{}\" (provider {}) - falling back to the conservative default rate "
                    + "(${}/{} per MTok in/out) so cost tracking and budget enforcement aren't silently "
                    + "bypassed. Add a VERIFIED rate to LlmPricingCatalog.RATES_BY_MODEL_PREFIX. "
                    + "Logged at most once per model every {} min; the {} counter carries the volume.",
                normalizedModel, providerTag, rate.inputPerMillion(), rate.outputPerMillion(),
                FALLBACK_WARN_INTERVAL.toMinutes(), PRICING_MISSING_METRIC
            );
        }
    }

    /**
     * Bounded model tag: the first {@link #MAX_TRACKED_UNPRICED_MODELS}
     * distinct unpriced models keep their own (length-capped) id; any model
     * after that shares {@link #OVERFLOW_MODEL_TAG}. The warn-bookkeeping map
     * doubles as the registry of tracked ids, so the tag set and the map stay
     * bounded together. The size check can overshoot by a few entries under
     * concurrent first sightings - still bounded, which is all this needs.
     */
    private String trackedModelTag(String normalizedModel) {
        String tag = normalizedModel.length() > MAX_MODEL_TAG_LENGTH
            ? normalizedModel.substring(0, MAX_MODEL_TAG_LENGTH) : normalizedModel;
        if (lastFallbackWarnNanosByModel.containsKey(tag)
            || lastFallbackWarnNanosByModel.size() < MAX_TRACKED_UNPRICED_MODELS) {
            return tag;
        }
        return OVERFLOW_MODEL_TAG;
    }

    private static BigDecimal costFor(long tokens, BigDecimal ratePerMillion) {
        return ratePerMillion
            .multiply(BigDecimal.valueOf(tokens))
            .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
    }

    /** $/MTok input and output rates for one model - see {@link #lookupRatesPerMillionTokens(String)}. */
    public record RatesPerMillionTokens(BigDecimal inputPerMillionUsd, BigDecimal outputPerMillionUsd) {}

    /**
     * Exposes this catalog's already-computed $/MTok rates for a model,
     * without pricing an actual call - added for the web model picker's cost
     * transparency badge (ModelPricingController), which needs "what does
     * this model cost" independent of any real token usage. Goes through the
     * exact same {@link #resolveRate(String)} prefix match and {@link
     * #defaultUnknownModelRate()} fallback as {@link #estimateCostUsd} so the
     * picker's badge and the actual metered/budgeted cost can never disagree
     * about which rate a given model id resolves to - one source of truth,
     * no separate pricing logic.
     *
     * @return the rates, or {@code null} only when {@code model} itself is
     *     null/blank (nothing to look up); an unrecognized-but-present model
     *     id still returns the conservative default rate, same as {@link
     *     #estimateCostUsd}.
     */
    /**
     * V484 - a view of this catalog that prices every call at exactly {@code $0}. Used for a
     * hosted-pool row the operator marked {@code zero_cost} (a local Ollama, vLLM or llama.cpp
     * server nobody bills per token), so {@code llm_call_log}, the per-user dollar budget and the
     * tenant spend counters all record a true zero instead of the unknown-model fallback rate.
     * Keeps the null contract of {@link #estimateCostUsd}: a call with no usable token counts still
     * returns {@code null}, so "cost unknown" and "cost zero" stay distinguishable.
     */
    public static LlmPricingCatalog zeroCost() {
        return ZeroCostView.INSTANCE;
    }

    private static final class ZeroCostView extends LlmPricingCatalog {
        private static final ZeroCostView INSTANCE = new ZeroCostView();

        private ZeroCostView() {
            super((MeterRegistry) null, System::nanoTime);
        }

        @Override
        public BigDecimal estimateCostUsd(
            String provider, String model, Long promptTokens, Long completionTokens,
            Long cacheCreationTokens, Long cacheReadTokens, Long reasoningTokens
        ) {
            if (model == null || promptTokens == null || completionTokens == null) {
                return null;
            }
            return BigDecimal.ZERO;
        }

        @Override
        public RatesPerMillionTokens lookupRatesPerMillionTokens(String model) {
            if (model == null || model.isBlank()) {
                return null;
            }
            return new RatesPerMillionTokens(BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    public RatesPerMillionTokens lookupRatesPerMillionTokens(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        Rate rate = resolveRate(model.toLowerCase(Locale.ROOT));
        if (rate == null) {
            rate = defaultUnknownModelRate();
        }
        return new RatesPerMillionTokens(rate.inputPerMillion(), rate.outputPerMillion());
    }

    private Rate resolveRate(String normalizedModel) {
        // Checked before the prefix table because ":free" is a SUFFIX - the
        // table below only ever does startsWith(), so a free variant could
        // never match there however many entries it gained. Deliberately
        // matched on the suffix rather than on a list of model ids: the free
        // catalog changes weekly, and a stale allowlist fails the expensive
        // way (billing free traffic) rather than the cheap one.
        if (normalizedModel.endsWith(":free")) {
            return FREE;
        }
        if (normalizedModel.startsWith("claude-sonnet-5")) {
            return Instant.now().isBefore(SONNET_5_STANDARD_PRICING_EFFECTIVE) ? SONNET_5_INTRODUCTORY : SONNET_5_STANDARD;
        }
        for (Map.Entry<String, Rate> entry : RATES_BY_MODEL_PREFIX.entrySet()) {
            if (normalizedModel.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Conservative fallback for a model this catalog doesn't recognize.
     *
     * <p><b>2026-09-13 FIX:</b> Changed from punitive highest-rate fallback
     * ($3/$15 per MTok) to conservative mid-range fallback ($0.50/$2.00 per MTok).
     * The previous implementation used the highest input/output rates in the catalog
     * (SONNET_5_STANDARD: $3/$15 per MTok), which caused budget exhaustion for
     * users of new/unknown models even with minimal token usage. This was reported
     * as "$3.00 for 4 messages" despite tokens not being exhausted.
     *
     * <p>The new conservative rate is deliberately mid-range rather than punitive:
     * $0.50 per MTok input and $2.00 per MTok output. This is high enough to
     * discourage relying on unknown models but low enough to avoid immediate budget
     * exhaustion for reasonable usage patterns. Once dynamic pricing discovery
     * is implemented (see V233__dynamic_pricing.sql design), this fallback will
     * only be used when provider pricing APIs are unavailable.
     *
     * <p><b>Note:</b> This is a temporary fix pending the full dynamic pricing
     * architecture implementation. The long-term solution involves fetching
     * real pricing from provider APIs and caching it alongside model metadata.
     */
    private Rate defaultUnknownModelRate() {
        // Conservative mid-range fallback: $0.50 input / $2.00 output per MTok
        // Changed from punitive $3/$15 per MTok (SONNET_5_STANDARD) on 2026-09-13
        return new Rate(bd("0.50"), bd("2.00"));
    }
}
