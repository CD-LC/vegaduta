package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * {@link LlmPricingCatalog} is the only source of the per-tenant cost
 * numbers surfaced on the Dashboard and LLM call logs - a wrong rate or a
 * broken prefix match would silently misreport real (estimated) spend to
 * every tenant. Had zero test coverage before this suite.
 *
 * <p><b>Time-bound test, not a flaw:</b> {@link
 * LlmPricingCatalog#estimateCostUsd} resolves {@code claude-sonnet-5}'s rate
 * based on {@code Instant.now()} vs a fixed 2026-09-01 cutover, with no
 * injectable clock (same class of limitation as {@code EditLockPolicy}
 * elsewhere in this codebase - see its own test suite's javadoc). The
 * 2026-09-01 cutover has passed, so this suite now asserts the STANDARD rate
 * ($3/$15 per MTok); it asserted the introductory rate before that date.
 */
class LlmPricingCatalogTest {

    private final LlmPricingCatalog catalog = new LlmPricingCatalog();

    // --- honest-null cases --------------------------------------------------

    @Test
    void nullModelReturnsNull() {
        assertNull(catalog.estimateCostUsd(null, 100L, 100L));
    }

    @Test
    void nullPromptTokensReturnsNull() {
        assertNull(catalog.estimateCostUsd("claude-opus-4-8", null, 100L));
    }

    @Test
    void nullCompletionTokensReturnsNull() {
        assertNull(catalog.estimateCostUsd("claude-opus-4-8", 100L, null));
    }

    @Test
    void unrecognizedModelFallsBackToConservativeDefaultRateInsteadOfNull() {
        // 2026-08-08 gap fix: a model this catalog can't price no longer
        // returns null (which let it slip through cost tracking / per-user
        // budget enforcement completely unpriced). Commit 4f429474 changed the
        // fallback from the punitive highest-rate-in-table to a conservative
        // mid-range $0.50 in / $2.00 out per MTok (see
        // LlmPricingCatalog.defaultUnknownModelRate), so 1M+1M tokens = $2.50.
        BigDecimal cost = catalog.estimateCostUsd("totally-unknown-model-v9", 1_000_000L, 1_000_000L);

        assertEquals(0, cost.compareTo(new BigDecimal("2.50")), "expected conservative-default $2.50, got " + cost);
    }

    // --- rate table + arithmetic ---------------------------------------------

    @Test
    void opus48RateIsAppliedCorrectly() {
        // input $5/MTok, output $25/MTok - 1M prompt + 1M completion tokens
        // should be exactly $5 + $25 = $30, no rounding involved.
        BigDecimal cost = catalog.estimateCostUsd("claude-opus-4-8", 1_000_000L, 1_000_000L);

        assertEquals(0, cost.compareTo(new BigDecimal("30")), "expected $30, got " + cost);
    }

    @Test
    void haiku45RateAppliesToSmallTokenCountsWithExactDivision() {
        // input $1/MTok, output $5/MTok: 500 prompt tokens -> $0.0005,
        // 200 completion tokens -> $0.001, total $0.0015 - divides evenly
        // so this also confirms no unexpected HALF_UP rounding kicks in.
        BigDecimal cost = catalog.estimateCostUsd("claude-haiku-4-5", 500L, 200L);

        assertEquals(0, cost.compareTo(new BigDecimal("0.0015")), "expected $0.0015, got " + cost);
    }

    @Test
    void haiku35RateIsDistinctFromHaiku45() {
        // $0.80/MTok input, $4/MTok output - deliberately different from
        // haiku-4-5's rate above so a copy-paste rate-table bug would fail
        // this test even if it passed the 4-5 test.
        BigDecimal cost = catalog.estimateCostUsd("claude-haiku-3-5", 1_000_000L, 1_000_000L);

        assertEquals(0, cost.compareTo(new BigDecimal("4.80")), "expected $4.80, got " + cost);
    }

    @Test
    void opus41RateIsDistinctFromNewerOpusFamilyMembers() {
        // $15/MTok input, $25 -> $75/MTok output - the one entry in the
        // table that breaks the "same rate as siblings" pattern the other
        // opus-4-x entries share, so a lazy shared-rate refactor bug would
        // be caught here.
        BigDecimal cost = catalog.estimateCostUsd("claude-opus-4-1", 1_000_000L, 1_000_000L);

        assertEquals(0, cost.compareTo(new BigDecimal("90")), "expected $90 (15 + 75), got " + cost);
    }

    // --- prefix matching (dated snapshot ids) ---------------------------------

    @Test
    void datedSnapshotSuffixStillResolvesToItsFamilysRate() {
        BigDecimal dated = catalog.estimateCostUsd("claude-opus-4-8-20260615", 1_000_000L, 1_000_000L);
        BigDecimal bare = catalog.estimateCostUsd("claude-opus-4-8", 1_000_000L, 1_000_000L);

        assertEquals(0, dated.compareTo(bare), "a dated snapshot id must resolve to the same rate as its bare family name");
    }

    @Test
    void moreSpecificPrefixWinsOverItsOwnBareFamilyPrefix() {
        // "claude-opus-4-8" is itself a prefix match for the bare
        // "claude-opus-4" entry too - table insertion order (more specific
        // entries listed first) is what makes the correct, more specific
        // rate win. This locks in that ordering dependency so a future
        // reordering of RATES_BY_MODEL_PREFIX can't silently misprice
        // opus-4-8/4-7/4-6/4-5 as the fallback bare-opus-4 rate ($15/$75).
        BigDecimal opus48 = catalog.estimateCostUsd("claude-opus-4-8", 1_000_000L, 1_000_000L);
        BigDecimal bareOpus4 = catalog.estimateCostUsd("claude-opus-4", 1_000_000L, 1_000_000L);

        assertEquals(0, opus48.compareTo(new BigDecimal("30")));
        assertEquals(0, bareOpus4.compareTo(new BigDecimal("90")));
    }

    @Test
    void modelIdIsMatchedCaseInsensitively() {
        BigDecimal upper = catalog.estimateCostUsd("Claude-Opus-4-8", 1_000_000L, 1_000_000L);
        BigDecimal lower = catalog.estimateCostUsd("claude-opus-4-8", 1_000_000L, 1_000_000L);

        assertEquals(0, upper.compareTo(lower));
    }

    // --- Sonnet 5 date-based pricing switch ------------------------------------

    @Test
    void sonnet5CurrentlyResolvesToStandardRateAfterTheSeptemberCutover() {
        // See class javadoc: this assumes "now" is on/after 2026-09-01.
        BigDecimal cost = catalog.estimateCostUsd("claude-sonnet-5", 1_000_000L, 1_000_000L);

        // standard: $3/MTok input + $15/MTok output = $18
        assertEquals(0, cost.compareTo(new BigDecimal("18")), "expected standard $18, got " + cost);
    }

    @Test
    void sonnet5DatedSnapshotAlsoUsesTheDateBasedSwitchNotTheStaticTable() {
        BigDecimal dated = catalog.estimateCostUsd("claude-sonnet-5-20260601", 1_000_000L, 1_000_000L);

        assertEquals(0, dated.compareTo(new BigDecimal("18")), "expected standard $18, got " + dated);
    }

    // --- prompt-cache write/read pricing (Anthropic) ---------------------------

    @Test
    void anthropicCacheTokensAreBilledAtTheirOwnRatesWhenSupplied() {
        // claude-opus-4-8: $5/MTok input -> cache write 1.25x = $6.25/MTok,
        // cache read 0.1x = $0.50/MTok. Zero base prompt/completion tokens
        // isolates the cache-only contribution: 1M write + 1M read = $6.75.
        BigDecimal cost = catalog.estimateCostUsd("claude-opus-4-8", 0L, 0L, 1_000_000L, 1_000_000L);

        assertEquals(0, cost.compareTo(new BigDecimal("6.75")), "expected $6.75 (6.25 write + 0.50 read), got " + cost);
    }

    @Test
    void cacheTokensAddOnTopOfBasePromptAndCompletionCost() {
        // opus-4-8 base: 1M prompt ($5) + 1M completion ($25) = $30, plus the
        // $6.75 cache contribution from the test above = $36.75 total.
        BigDecimal cost = catalog.estimateCostUsd("claude-opus-4-8", 1_000_000L, 1_000_000L, 1_000_000L, 1_000_000L);

        assertEquals(0, cost.compareTo(new BigDecimal("36.75")), "expected $36.75, got " + cost);
    }

    @Test
    void nullOrZeroCacheTokensAddNothing() {
        BigDecimal withNulls = catalog.estimateCostUsd("claude-opus-4-8", 1_000_000L, 1_000_000L, null, null);
        BigDecimal withZeros = catalog.estimateCostUsd("claude-opus-4-8", 1_000_000L, 1_000_000L, 0L, 0L);
        BigDecimal withoutCacheArgs = catalog.estimateCostUsd("claude-opus-4-8", 1_000_000L, 1_000_000L);

        assertEquals(0, withNulls.compareTo(new BigDecimal("30")));
        assertEquals(0, withZeros.compareTo(new BigDecimal("30")));
        assertEquals(0, withoutCacheArgs.compareTo(new BigDecimal("30")));
    }

    @Test
    void cacheTokensForAModelWithNoKnownCacheRateFallBackToItsBaseInputRate() {
        // gpt-4o-mini ($0.15/MTok input) has no Anthropic-style cache rate in
        // this catalog, so cache tokens must still be priced - at the plain
        // base input rate - rather than silently dropped from the estimate.
        // 1M write + 1M read at $0.15/MTok each = $0.30, on top of $0 base.
        BigDecimal cost = catalog.estimateCostUsd("gpt-4o-mini", 0L, 0L, 1_000_000L, 1_000_000L);

        assertEquals(0, cost.compareTo(new BigDecimal("0.30")), "expected $0.30 (cache billed at base input rate), got " + cost);
    }

    @Test
    void cacheOverloadStillReturnsNullWhenBaseTokenCountsAreMissing() {
        assertNull(catalog.estimateCostUsd("claude-opus-4-8", null, 100L, 1L, 1L));
        assertNull(catalog.estimateCostUsd(null, 100L, 100L, 1L, 1L));
    }

    /**
     * REGRESSION: Azure OpenAI deployment names use DASHES, not dots, and the
     * miss was not cosmetic — it silently mis-ordered the whole hosted pool.
     *
     * <p>Azure addresses a model by DEPLOYMENT name and a deployment name cannot
     * contain "."; production runs `gpt-4-1-mini`. That matched no prefix here
     * and fell through to {@code defaultUnknownModelRate()} — deliberately the
     * HIGHEST rate in the table. {@code FreeTierChatClientService#orderForFailover}
     * then sorts the pool cheapest-first, so the LIVE Azure provider priced as
     * the most expensive row and sorted LAST, behind a stale Vertex us-central1
     * row that priced correctly. Turns were attempting a dead US provider first.
     *
     * <p>Asserting the dashed id costs the SAME as the dotted one is what keeps
     * the two spellings from drifting apart again.
     */
    @Test
    void azureDashedDeploymentNamesPriceTheSameAsTheirDottedModelIds() {
        // 1M in + 1M out at $0.40/$1.60 = $2.00 for gpt-4.1-mini.
        BigDecimal dotted = catalog.estimateCostUsd("gpt-4.1-mini", 1_000_000L, 1_000_000L);
        BigDecimal dashed = catalog.estimateCostUsd("gpt-4-1-mini", 1_000_000L, 1_000_000L);

        assertEquals(0, dashed.compareTo(dotted),
            "Azure deployment name gpt-4-1-mini must price identically to gpt-4.1-mini, got "
                + dashed + " vs " + dotted);
        assertEquals(0, dashed.compareTo(new BigDecimal("2.00")),
            "expected $2.00 for 1M in + 1M out, got " + dashed);
    }

    /**
     * The dashed keys are prefix matches in a LinkedHashMap, so insertion order
     * decides which wins. `gpt-4-1-mini` must resolve to the mini rate, NOT to
     * the cheaper-to-type `gpt-4-1` base entry that follows it.
     */
    @Test
    void dashedMiniAndNanoWinOverTheDashedBaseEntry() {
        assertEquals(0,
            catalog.estimateCostUsd("gpt-4-1-nano", 1_000_000L, 1_000_000L).compareTo(new BigDecimal("0.50")),
            "gpt-4-1-nano should price at $0.10 in + $0.40 out");
        assertEquals(0,
            catalog.estimateCostUsd("gpt-4-1", 1_000_000L, 1_000_000L).compareTo(new BigDecimal("10")),
            "gpt-4-1 (base) should price at $2 in + $8 out");
    }

    // --- Meta Model API (Muse Spark) -----------------------------------------

    /**
     * "muse-spark-1.3-contributor" starts with "muse-spark-1.3", so the
     * contributor rows must be inserted BEFORE the bare family rows or the
     * cheap (Meta-trains-on-it) tier would be billed at the standard rate.
     */
    @Test
    void museSparkContributorVariantPricesAtItsOwnCheapRateNotTheStandardFamilyRate() {
        assertEquals(0,
            catalog.estimateCostUsd("muse-spark-1.3-contributor", 1_000_000L, 1_000_000L).compareTo(new BigDecimal("0.30")),
            "muse-spark-1.3-contributor should price at $0.10 in + $0.20 out");
        assertEquals(0,
            catalog.estimateCostUsd("muse-spark-1.2-contributor", 1_000_000L, 1_000_000L).compareTo(new BigDecimal("0.30")),
            "muse-spark-1.2-contributor should price at $0.10 in + $0.20 out");
    }

    @Test
    void museSparkStandardModelsPriceAtTheStandardRate() {
        for (String model : new String[] {"muse-spark-1.3", "muse-spark-1.2", "muse-spark-1.1"}) {
            assertEquals(0,
                catalog.estimateCostUsd(model, 1_000_000L, 1_000_000L).compareTo(new BigDecimal("5.50")),
                model + " should price at $1.25 in + $4.25 out");
        }
    }

    @Test
    void museSparkCachedInputIsBilledAtTheCachedRateAndCacheWritesAtThePlainInputRate() {
        // dev.meta.ai: cached input $0.15/MTok; no separate cache-write price
        // is published, so writes fall back to the $1.25 input rate.
        BigDecimal cost = catalog.estimateCostUsd("muse-spark-1.3", 0L, 0L, 1_000_000L, 1_000_000L);

        assertEquals(0, cost.compareTo(new BigDecimal("1.40")), "expected $1.40 (1.25 write + 0.15 read), got " + cost);
    }

    /**
     * Through the OPENROUTER provider the id arrives vendor-prefixed and the
     * advisor passes it through raw; without its own rows it would fall to the
     * unknown-model default rate. Contributor-first ordering applies here too.
     */
    @Test
    void museSparkViaOpenRouterSpellingPricesLikeTheDirectId() {
        assertEquals(0,
            catalog.estimateCostUsd("meta/muse-spark-1.3", 1_000_000L, 1_000_000L).compareTo(new BigDecimal("5.50")),
            "meta/muse-spark-1.3 should price at $1.25 in + $4.25 out");
        assertEquals(0,
            catalog.estimateCostUsd("meta/muse-spark-1.3-contributor", 1_000_000L, 1_000_000L).compareTo(new BigDecimal("0.30")),
            "meta/muse-spark-1.3-contributor should price at $0.10 in + $0.20 out");
    }

    @Test
    void openRouterFreeModelsCostNothingRatherThanFallingBackToTheUnknownRate() {
        // A ":free" model matches no prefix in RATES_BY_MODEL_PREFIX (the table
        // only does startsWith, and ":free" is a suffix), so before this rule it
        // fell through to defaultUnknownModelRate() — $0.50/$2.00 per MTok on
        // traffic that costs nothing. That figure debits per-user budgets and
        // drives enforcement, so a free-tier user would be cut off for spend
        // that never happened.
        BigDecimal cost = catalog.estimateCostUsd(
            "nex-agi/nex-n2.5-mini:free", 1_000_000L, 1_000_000L, null, null);

        assertEquals(0, BigDecimal.ZERO.compareTo(cost),
            "a :free model must cost exactly zero, got " + cost);
    }

    @Test
    void theFreeSuffixRuleDoesNotZeroRateAPaidModelOfASimilarName() {
        // Guards the suffix check against over-reach: only a trailing ":free"
        // counts, not the substring appearing anywhere in the id.
        BigDecimal paid = catalog.estimateCostUsd(
            "somevendor/free-tier-preview", 1_000_000L, 1_000_000L, null, null);

        assertTrue(paid.compareTo(BigDecimal.ZERO) > 0,
            "a model merely containing 'free' must still be billed, got " + paid);
    }

    // --- lookupRatesPerMillionTokens (web model-picker cost badge) -----------

    @Test
    void lookupRatesReturnsNullOnlyForANullOrBlankModelId() {
        assertNull(catalog.lookupRatesPerMillionTokens(null));
        assertNull(catalog.lookupRatesPerMillionTokens(""));
        assertNull(catalog.lookupRatesPerMillionTokens("   "));
    }

    @Test
    void lookupRatesMatchesTheSamePerMillionRateEstimateCostUsdActuallyBills() {
        // Same prefix-matched rate as the arithmetic tests above (claude-opus-4-8:
        // $5/$25 per MTok) - the badge and the real metered cost must never disagree.
        LlmPricingCatalog.RatesPerMillionTokens rates = catalog.lookupRatesPerMillionTokens("claude-opus-4-8");

        assertEquals(0, rates.inputPerMillionUsd().compareTo(new BigDecimal("5")));
        assertEquals(0, rates.outputPerMillionUsd().compareTo(new BigDecimal("25")));
    }

    @Test
    void lookupRatesFallsBackToTheConservativeDefaultForAnUnknownModel() {
        LlmPricingCatalog.RatesPerMillionTokens rates = catalog.lookupRatesPerMillionTokens("totally-unknown-model-v9");

        assertEquals(0, rates.inputPerMillionUsd().compareTo(new BigDecimal("0.50")));
        assertEquals(0, rates.outputPerMillionUsd().compareTo(new BigDecimal("2.00")));
    }

    @Test
    void lookupRatesIsCaseInsensitiveAndPrefixMatchesDatedSnapshots() {
        LlmPricingCatalog.RatesPerMillionTokens upper = catalog.lookupRatesPerMillionTokens("Claude-Opus-4-8");
        LlmPricingCatalog.RatesPerMillionTokens dated = catalog.lookupRatesPerMillionTokens("claude-opus-4-8-20260615");

        assertEquals(0, upper.inputPerMillionUsd().compareTo(new BigDecimal("5")));
        assertEquals(0, dated.inputPerMillionUsd().compareTo(new BigDecimal("5")));
    }

    @Test
    void lookupRatesReflectsAFreeModelAsZeroZero() {
        LlmPricingCatalog.RatesPerMillionTokens rates = catalog.lookupRatesPerMillionTokens("nex-agi/nex-n2.5-mini:free");

        assertEquals(0, rates.inputPerMillionUsd().compareTo(BigDecimal.ZERO));
        assertEquals(0, rates.outputPerMillionUsd().compareTo(BigDecimal.ZERO));
    }
}
