package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.GenerateContentResponseUsageMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage;

/**
 * Locks in the two properties {@link CacheTokenAccounting} exists to guarantee, both of which are
 * easy to break by "simplifying" it later:
 *
 * <ol>
 *   <li><b>Absent is never zero.</b> A provider that reports no cache dimension must produce {@code
 *       null}, not {@code 0}, because a measured total cache miss (a real, expensive regression) is
 *       also zero and the two must stay distinguishable. This is what stops the Micrometer layer
 *       from publishing a permanent fake-zero series for every provider that has no prompt cache.
 *   <li><b>The inclusive/exclusive split is respected per provider.</b> Anthropic's {@code
 *       inputTokens} EXCLUDES cached reads while OpenAI's and Gemini's {@code promptTokens}
 *       INCLUDES them. Getting that backwards either double-counts a tenant's input tokens or
 *       reports a cache hit ratio computed against the wrong denominator - and in the cost path it
 *       would double-BILL them, which is why {@code billableCache*Tokens()} is asserted separately
 *       from the raw counts.
 * </ol>
 *
 * <p>Every provider usage shape here is constructed from the real Spring AI 2.0.1 / vendor SDK /
 * Google GenAI SDK types on the compile classpath (not a hand-rolled stub), so a future dependency
 * bump that reshapes one of them breaks this test rather than silently making the accounting wrong
 * at runtime - which is the only failure mode of this class that would otherwise be invisible.
 */
class CacheTokenAccountingTest {

    // ------------------------------------------------------------------
    // Anthropic - the exclusive-billing provider
    // ------------------------------------------------------------------

    @Test
    void anthropicSplitsWithoutSubtractingBecausePromptTokensAlreadyExcludeCacheTokens() {
        // 200 fresh + 1800 read from a warm prefix + 0 written this call.
        // Anthropic's input_tokens is 200, NOT 2000 - Spring AI maps
        // promptTokens straight from it.
        Usage usage = anthropicUsage(200, 120, 0, 1800);

        CacheTokenAccounting accounting = CacheTokenAccounting.from(usage);

        assertTrue(accounting.isPresent());
        assertFalse(
                accounting.cacheReadsIncludedInPromptTokens(),
                "Anthropic reports cache buckets alongside input_tokens, not inside it");
        assertEquals(1800L, accounting.cacheReadInputTokens());
        assertEquals(0L, accounting.cacheWriteInputTokens());
        assertEquals(
                200L,
                accounting.uncachedInputTokens(),
                "promptTokens IS the uncached bucket here - no subtraction");
        assertEquals(2000L, accounting.totalInputTokens());
        assertEquals(0.9d, accounting.hitRatio(), 1e-9);
    }

    @Test
    void anthropicCacheBucketsStayBillableAsTheirOwnCostLineItems() {
        CacheTokenAccounting accounting =
                CacheTokenAccounting.from(anthropicUsage(200, 120, 4096, 1800));

        // These two are what LlmMetricsAdvisor hands to
        // LlmPricingCatalog.estimateCostUsd's 5-arg overload, which prices
        // them on top of promptTokens - arithmetic that is only correct for
        // a provider whose buckets are disjoint, as Anthropic's are.
        assertEquals(1800L, accounting.billableCacheReadTokens());
        assertEquals(4096L, accounting.billableCacheWriteTokens());
    }

    @Test
    void anthropicReportingAnExplicitZeroReadIsAMeasuredMissNotAnAbsence() {
        // The regression this whole class was written to make visible: a
        // prompt edit invalidated the shared prefix, so every call now pays
        // full price for input it used to read from cache. The provider says
        // so - cache_read_input_tokens: 0 - and that MUST survive as a real
        // 0.0 hit ratio rather than collapsing to "unknown".
        CacheTokenAccounting accounting =
                CacheTokenAccounting.from(anthropicUsage(2000, 120, 0, 0));

        assertTrue(accounting.isPresent(), "an explicit zero from the provider is a measurement");
        assertEquals(0L, accounting.cacheReadInputTokens());
        assertEquals(0.0d, accounting.hitRatio(), 1e-9, "a total miss must report 0.0, never null");
    }

    @Test
    void anthropicOmittingBothCacheFieldsIsAbsentNotAZeroHitRatio() {
        // A request that carried no cache_control block at all: Anthropic
        // omits both fields, so nothing was measured. Reporting 0.0 here
        // would be indistinguishable from the measured-miss case above and
        // would make the fleet hit ratio quietly wrong.
        CacheTokenAccounting accounting =
                CacheTokenAccounting.from(anthropicUsage(2000, 120, null, null));

        assertSame(CacheTokenAccounting.ABSENT, accounting);
        assertFalse(accounting.isPresent());
        assertNull(accounting.hitRatio());
        assertNull(accounting.totalInputTokens());
    }

    @Test
    void anthropicReportingOnlyAWriteStillCountsAsMeasured() {
        // First call against a cold prefix: everything was written, nothing
        // read. Anthropic can report cache_creation with cache_read absent.
        CacheTokenAccounting accounting =
                CacheTokenAccounting.from(anthropicUsage(150, 90, 4096, null));

        assertTrue(accounting.isPresent());
        assertEquals(4096L, accounting.cacheWriteInputTokens());
        assertNull(accounting.cacheReadInputTokens());
        assertNull(
                accounting.hitRatio(),
                "no read count reported means the ratio is unknown, not zero");
        assertEquals(
                4246L,
                accounting.totalInputTokens(),
                "an absent read bucket contributes nothing to the total");
    }

    // ------------------------------------------------------------------
    // OpenAI - the inclusive-billing provider
    // ------------------------------------------------------------------

    @Test
    void openAiCachedTokensAreSubtractedOutOfPromptTokens() {
        // OpenAI's prompt_tokens (2000) ALREADY contains the 1024 cached
        // ones, so the uncached bucket is the remainder - not 2000.
        Usage usage = openAiUsage(2000, 120, 1024);

        CacheTokenAccounting accounting = CacheTokenAccounting.from(usage);

        assertTrue(accounting.isPresent());
        assertTrue(accounting.cacheReadsIncludedInPromptTokens());
        assertEquals(1024L, accounting.cacheReadInputTokens());
        assertEquals(976L, accounting.uncachedInputTokens());
        assertEquals(
                2000L,
                accounting.totalInputTokens(),
                "for an inclusive provider the accounted total must equal promptTokens, not exceed it");
        assertEquals(1024d / 2000d, accounting.hitRatio(), 1e-9);
    }

    @Test
    void openAiReportsNoCacheWriteCountSoTheWriteBucketStaysAbsent() {
        CacheTokenAccounting accounting = CacheTokenAccounting.from(openAiUsage(2000, 120, 1024));

        assertNull(
                accounting.cacheWriteInputTokens(),
                "OpenAI's caching is automatic and it reports no write count - absent, not a zero write");
    }

    @Test
    void openAiCachedTokensAreNotFedToTheCostEstimateBecauseThatWouldDoubleBillThem() {
        CacheTokenAccounting accounting = CacheTokenAccounting.from(openAiUsage(2000, 120, 1024));

        // LlmPricingCatalog prices promptTokens at the base input rate and
        // ADDS the cache buckets. Since those 1024 tokens are already inside
        // promptTokens, handing them over as a separate line item would bill
        // them twice - so the billable accessors withhold them, leaving the
        // cost estimate byte-identical to what it was before OpenAI cache
        // reads were extracted at all.
        assertNull(accounting.billableCacheReadTokens());
        assertNull(accounting.billableCacheWriteTokens());
    }

    @Test
    void openAiWithoutPromptTokensDetailsIsAbsent() {
        // Non-caching OpenAI-compatible endpoints (OpenRouter, Grok via
        // OpenAiApi - see this module's pom.xml comment) routinely omit the
        // details object entirely.
        Usage usage = SdkUsageFixtures.openAi(2000, 120, null);

        assertSame(CacheTokenAccounting.ABSENT, CacheTokenAccounting.from(usage));
    }

    @Test
    void moreCachedTokensThanPromptTokensClampsRatherThanGoingNegative() {
        // Defensive: a provider bug here would otherwise push a negative
        // value into a Micrometer counter, which is unrecoverable for the
        // life of the process.
        CacheTokenAccounting accounting = CacheTokenAccounting.from(openAiUsage(500, 10, 900));

        assertEquals(0L, accounting.uncachedInputTokens());
        assertEquals(1.0d, accounting.hitRatio(), 1e-9);
    }

    // ------------------------------------------------------------------
    // Google GenAI - inclusive, and the Usage implementation itself
    // ------------------------------------------------------------------

    @Test
    void googleGenAiCachedContentIsReadOffTheUsageObjectItselfNotNativeUsage() {
        // GoogleGenAiUsage extends DefaultUsage rather than hanging a native
        // record off getNativeUsage(), so this branch has to match on the
        // Usage type - the reason CacheTokenAccounting.from checks it first.
        GoogleGenAiUsage usage =
                GoogleGenAiUsage.from(
                        GenerateContentResponseUsageMetadata.builder()
                                .promptTokenCount(5000)
                                .candidatesTokenCount(200)
                                .totalTokenCount(5200)
                                .cachedContentTokenCount(4000)
                                .build());

        CacheTokenAccounting accounting = CacheTokenAccounting.from(usage);

        assertTrue(accounting.isPresent());
        assertTrue(accounting.cacheReadsIncludedInPromptTokens());
        assertEquals(4000L, accounting.cacheReadInputTokens());
        assertEquals(1000L, accounting.uncachedInputTokens());
        assertNull(
                accounting.cacheWriteInputTokens(),
                "creating a Gemini cache object is its own API call (GoogleGenAiCacheManager), not a field on a response");
        assertEquals(0.8d, accounting.hitRatio(), 1e-9);
        assertNull(
                accounting.billableCacheReadTokens(),
                "inclusive provider - already inside promptTokens");
    }

    @Test
    void googleGenAiWithoutCachedContentIsAbsent() {
        GoogleGenAiUsage usage =
                GoogleGenAiUsage.from(
                        GenerateContentResponseUsageMetadata.builder()
                                .promptTokenCount(5000)
                                .candidatesTokenCount(200)
                                .totalTokenCount(5200)
                                .build());

        assertSame(CacheTokenAccounting.ABSENT, CacheTokenAccounting.from(usage));
    }

    // ------------------------------------------------------------------
    // Everything else
    // ------------------------------------------------------------------

    @Test
    void aPlainUsageWithNoNativePayloadIsAbsent() {
        // This is also the real multi-iteration tool-turn shape: Spring AI's
        // UsageCalculator.getCumulativeUsage sums iterations into a
        // DefaultUsage built WITHOUT a native usage object, so the cache
        // split genuinely is not recoverable on those turns. Absent is the
        // honest answer - see CacheTokenAccounting's javadoc.
        assertSame(
                CacheTokenAccounting.ABSENT,
                CacheTokenAccounting.from(new DefaultUsage(2000, 120, 2120)));
    }

    @Test
    void nullUsageIsAbsentRatherThanAnException() {
        assertSame(CacheTokenAccounting.ABSENT, CacheTokenAccounting.from(null));
    }

    @Test
    void anAllZeroInputCallHasNoRatioBecauseZeroOverZeroIsUndefined() {
        CacheTokenAccounting accounting = CacheTokenAccounting.from(anthropicUsage(0, 0, 0, 0));

        assertTrue(accounting.isPresent(), "the provider did report the dimensions");
        assertNull(
                accounting.hitRatio(),
                "0/0 is undefined, and must not be reported as a 0% hit rate");
    }

    // ------------------------------------------------------------------
    // Fixtures - real provider types, deliberately not stubs
    // ------------------------------------------------------------------

    /**
     * Exactly what Spring AI 2's AnthropicChatModel#getDefaultUsage builds: promptTokens =
     * inputTokens alone.
     */
    private static Usage anthropicUsage(
            Integer inputTokens,
            Integer outputTokens,
            Integer cacheCreationInputTokens,
            Integer cacheReadInputTokens) {
        return SdkUsageFixtures.anthropic(
                inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens);
    }

    /** Exactly what Spring AI 2's OpenAiChatModel#getDefaultUsage builds. */
    private static Usage openAiUsage(
            Integer promptTokens, Integer completionTokens, Integer cachedTokens) {
        return SdkUsageFixtures.openAi(promptTokens, completionTokens, cachedTokens);
    }
}
