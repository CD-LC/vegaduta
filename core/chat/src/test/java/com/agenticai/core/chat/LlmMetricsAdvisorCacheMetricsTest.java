package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderName;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * Covers the cached-vs-uncached token accounting {@link LlmMetricsAdvisor} emits, kept in its own
 * class rather than bolted onto {@link LlmMetricsAdvisorTest} (which is entirely about the {@code
 * error_message} shape on a FAILED call) because every test here needs a SUCCESSFUL call with real
 * provider usage metadata - a different fixture, not a different assertion.
 *
 * <p>What is actually being defended here is the "absent is not zero" rule. {@code
 * MeterRegistry.counter(name, tags)} REGISTERS a meter as a side effect of looking it up, so the
 * natural-looking implementation - null-coalesce each count to 0 and increment unconditionally -
 * would publish a permanent {@code 0} series for every provider that has no prompt cache, and that
 * series is indistinguishable in Prometheus from a real total cache miss. The assertions below
 * therefore check for the ABSENCE of meters as carefully as for their values.
 *
 * <p>The second thing under test is that this whole addition is cost-neutral. {@code llm.cost.usd}
 * and the {@code cache_creation_tokens}/{@code cache_read_tokens} values handed to {@link
 * LlmCallLogService#record} must come out exactly as they did before the accounting existed -
 * unchanged for Anthropic, and still null for the providers whose cached tokens are already inside
 * {@code promptTokens} and would double-bill if priced separately.
 */
class LlmMetricsAdvisorCacheMetricsTest {

    private final LlmCallLogService callLogService = mock(LlmCallLogService.class);
    private final CallAdvisorChain chain = mock(CallAdvisorChain.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private LlmMetricsAdvisor advisorFor(ProviderName provider, String model) {
        return new LlmMetricsAdvisor(
                new LlmCallMetricsService(),
                callLogService,
                provider,
                model,
                meterRegistry,
                new LlmPricingCatalog());
    }

    private static ChatClientRequest request() {
        return new ChatClientRequest(new Prompt("hi"), Map.of());
    }

    /** Wraps {@code usage} in the minimal real response shape the advisor reads it out of. */
    private static ChatClientResponse responseWith(Usage usage) {
        ChatResponse chatResponse =
                new ChatResponse(
                        List.of(new Generation(new AssistantMessage("ok"))),
                        ChatResponseMetadata.builder().usage(usage).build());
        return new ChatClientResponse(chatResponse, Map.of());
    }

    private void runSuccessfulCall(LlmMetricsAdvisor advisor, Usage usage) {
        when(chain.nextCall(any())).thenReturn(responseWith(usage));
        advisor.adviseCall(request(), chain);
    }

    /** Total of a counter by name, or {@code null} when the meter was never registered at all. */
    private Double counterTotal(String name) {
        try {
            return meterRegistry.get(name).counter().count();
        } catch (MeterNotFoundException e) {
            return null;
        }
    }

    @Test
    void anthropicCallSplitsInputTokensIntoThreeDisjointCountersAndAHitRatio() {
        // 200 fresh, 1800 read from a warm prefix, 4096 written. Anthropic's
        // input_tokens is 200 - the other two are separate buckets.
        runSuccessfulCall(
                advisorFor(ProviderName.ANTHROPIC, "claude-sonnet-4-5"),
                anthropicUsage(200, 120, 4096, 1800));

        assertEquals(1800.0, counterTotal("llm.input.tokens.cache.read"));
        assertEquals(4096.0, counterTotal("llm.input.tokens.cache.write"));
        assertEquals(200.0, counterTotal("llm.input.tokens.uncached"));
        assertEquals(1.0, counterTotal("llm.cache.accounted.calls"));

        // 1800 / (1800 + 4096 + 200). Recorded as a per-call distribution
        // rather than a gauge so a burst of misses is visible even while the
        // token-weighted aggregate stays high.
        assertEquals(
                1800.0 / 6096.0, meterRegistry.get("llm.cache.hit.ratio").summary().mean(), 1e-9);
        assertEquals(1L, meterRegistry.get("llm.cache.hit.ratio").summary().count());
    }

    @Test
    void reasoningTokensGetTheirOwnCounterLabelledIncludedOrAdditionalAndAbsentMeansNoSeries() {
        // OpenAI: 400 of the 500 completion tokens are reasoning, already inside completion.
        OpenAiApi.Usage included =
                new OpenAiApi.Usage(
                        500,
                        100,
                        600,
                        null,
                        new OpenAiApi.Usage.CompletionTokenDetails(400, null, null, null));
        runSuccessfulCall(
                advisorFor(ProviderName.OPENAI, "gpt-5"),
                new DefaultUsage(100, 500, 600, included));
        assertEquals(
                400.0,
                meterRegistry
                        .get("llm.output.tokens.reasoning")
                        .tag("accounting", "included")
                        .counter()
                        .count());

        // Gemini-compat shape: total proves the 300 thoughts sit outside completion.
        OpenAiApi.Usage additional =
                new OpenAiApi.Usage(
                        50,
                        100,
                        450,
                        null,
                        new OpenAiApi.Usage.CompletionTokenDetails(300, null, null, null));
        runSuccessfulCall(
                advisorFor(ProviderName.OPENAI, "gpt-5"),
                new DefaultUsage(100, 50, 450, additional));
        assertEquals(
                300.0,
                meterRegistry
                        .get("llm.output.tokens.reasoning")
                        .tag("accounting", "additional")
                        .counter()
                        .count());
    }

    @Test
    void anthropicThinkingCallRegistersNoReasoningSeries() {
        runSuccessfulCall(
                advisorFor(ProviderName.ANTHROPIC, "claude-sonnet-4-5"),
                anthropicUsage(200, 5000, 0, 0));
        assertNull(
                counterTotal("llm.output.tokens.reasoning"),
                "Anthropic reports no separate thinking count");
    }

    @Test
    void everyNewCacheMeterCarriesTheSameTenantProviderModelTagsAsTheExistingOnes() {
        // If the cache meters were tagged differently they could not be
        // joined against llm.cost.usd, which is the entire point of having
        // them - "which tenant's cache collapsed and what did it cost".
        runSuccessfulCall(
                advisorFor(ProviderName.ANTHROPIC, "claude-sonnet-4-5"),
                anthropicUsage(200, 120, 0, 1800));

        for (String name :
                List.of(
                        "llm.input.tokens.cache.read",
                        "llm.input.tokens.cache.write",
                        "llm.input.tokens.uncached",
                        "llm.cache.accounted.calls",
                        "llm.cache.hit.ratio")) {
            Meter meter = meterRegistry.get(name).meter();
            assertEquals(
                    "system",
                    meter.getId().getTag("tenant"),
                    name
                            + " must fall back to the literal \"system\" tenant tag, never omit the tag");
            assertEquals(
                    "ANTHROPIC",
                    meter.getId().getTag("provider"),
                    name + " must carry the provider tag");
            assertEquals(
                    "claude-sonnet-4-5",
                    meter.getId().getTag("model"),
                    name + " must carry the model tag");
        }
    }

    @Test
    void aProviderThatReportsNoCacheDimensionRegistersNoCacheMetersAtAll() {
        // The core "absent is not zero" assertion. A zero-valued series here
        // would be indistinguishable in Prometheus from a real total cache
        // miss, which is the exact regression these meters exist to catch.
        runSuccessfulCall(
                advisorFor(ProviderName.OPENAI, "gpt-5"), new DefaultUsage(2000, 120, 2120));

        assertNull(counterTotal("llm.cache.accounted.calls"));
        assertNull(counterTotal("llm.input.tokens.cache.read"));
        assertNull(counterTotal("llm.input.tokens.cache.write"));
        assertNull(counterTotal("llm.input.tokens.uncached"));
        assertThrows(
                MeterNotFoundException.class,
                () -> meterRegistry.get("llm.cache.hit.ratio").summary());

        // ...while the pre-existing meters are untouched by any of this.
        assertEquals(1.0, counterTotal("llm.calls"));
    }

    @Test
    void aMeasuredZeroCacheReadStillRegistersTheSeriesWithAZeroRatio() {
        // The counterpart to the test above: the provider DID report, and it
        // reported a total miss. That must be loud, not absent.
        runSuccessfulCall(
                advisorFor(ProviderName.ANTHROPIC, "claude-sonnet-4-5"),
                anthropicUsage(2000, 120, 0, 0));

        assertEquals(1.0, counterTotal("llm.cache.accounted.calls"));
        assertEquals(0.0, counterTotal("llm.input.tokens.cache.read"));
        assertEquals(2000.0, counterTotal("llm.input.tokens.uncached"));
        assertEquals(0.0, meterRegistry.get("llm.cache.hit.ratio").summary().mean(), 1e-9);
    }

    @Test
    void anInclusiveProviderReportsAReadCounterButNoWriteCounter() {
        // OpenAI charges nothing to write and reports no write count, so the
        // write dimension is genuinely unknown - one absent dimension on an
        // otherwise-measured call.
        runSuccessfulCall(advisorFor(ProviderName.OPENAI, "gpt-5"), openAiUsage(2000, 120, 1600));

        assertEquals(1600.0, counterTotal("llm.input.tokens.cache.read"));
        assertEquals(
                400.0,
                counterTotal("llm.input.tokens.uncached"),
                "prompt_tokens already contains the cached ones, so the uncached bucket is the remainder");
        assertNull(
                counterTotal("llm.input.tokens.cache.write"),
                "OpenAI reports no cache-write count - the series must not exist rather than read zero");
    }

    @Test
    void anInclusiveProvidersCachedTokensNeverReachTheDurableRowOrTheCostEstimate() {
        // Strictly-additive guarantee: LlmPricingCatalog prices promptTokens
        // at the base rate and ADDS the cache buckets, so passing an
        // inclusive provider's cached count through would double-bill it.
        // Those two arguments must stay null, exactly as they were before
        // OpenAI cache reads were extracted at all.
        runSuccessfulCall(advisorFor(ProviderName.OPENAI, "gpt-5"), openAiUsage(2000, 120, 1600));

        ArgumentCaptor<Long> cacheCreation = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> cacheRead = ArgumentCaptor.forClass(Long.class);
        verify(callLogService)
                .record(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(true),
                        any(),
                        any(),
                        any(),
                        any(),
                        anyLong(),
                        any(),
                        any(),
                        any(),
                        any(),
                        cacheCreation.capture(),
                        cacheRead.capture(),
                        any(),
                        any(),
                        any());

        assertNull(cacheCreation.getValue());
        assertNull(cacheRead.getValue());
    }

    @Test
    void anExclusiveProvidersCacheTokensStillReachTheDurableRowUnchanged() {
        runSuccessfulCall(
                advisorFor(ProviderName.ANTHROPIC, "claude-sonnet-4-5"),
                anthropicUsage(200, 120, 4096, 1800));

        ArgumentCaptor<Long> cacheCreation = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> cacheRead = ArgumentCaptor.forClass(Long.class);
        verify(callLogService)
                .record(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(true),
                        any(),
                        any(),
                        any(),
                        any(),
                        anyLong(),
                        any(),
                        any(),
                        any(),
                        any(),
                        cacheCreation.capture(),
                        cacheRead.capture(),
                        any(),
                        any(),
                        any());

        assertEquals(4096L, cacheCreation.getValue());
        assertEquals(1800L, cacheRead.getValue());
        // And the cost counter still exists and is nonzero, i.e. the cost
        // path was not disturbed by routing these through the accounting.
        Double cost = counterTotal("llm.cost.usd");
        assertNotNull(cost);
        assertTrue(
                cost > 0.0,
                "an Anthropic call with real token counts must still accrue an estimated cost");
    }

    @Test
    void aFailedCallEmitsNoCacheMetersBecauseThereIsNoUsageToSplit() {
        when(chain.nextCall(any())).thenThrow(new RuntimeException("boom"));
        LlmMetricsAdvisor advisor = advisorFor(ProviderName.ANTHROPIC, "claude-sonnet-4-5");

        assertThrows(RuntimeException.class, () -> advisor.adviseCall(request(), chain));

        assertEquals(1.0, counterTotal("llm.calls"));
        assertEquals(1.0, counterTotal("llm.call.errors"));
        assertNull(
                counterTotal("llm.cache.accounted.calls"),
                "a failed call measured nothing about caching - it must not count as a miss");
    }

    // ------------------------------------------------------------------
    // Fixtures - see CacheTokenAccountingTest for the component orders
    // ------------------------------------------------------------------

    private static Usage anthropicUsage(
            int inputTokens,
            int outputTokens,
            Integer cacheCreationInputTokens,
            Integer cacheReadInputTokens) {
        return new DefaultUsage(
                inputTokens,
                outputTokens,
                inputTokens + outputTokens,
                new AnthropicApi.Usage(
                        inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens));
    }

    private static Usage openAiUsage(int promptTokens, int completionTokens, Integer cachedTokens) {
        return new DefaultUsage(
                promptTokens,
                completionTokens,
                promptTokens + completionTokens,
                new OpenAiApi.Usage(
                        completionTokens,
                        promptTokens,
                        promptTokens + completionTokens,
                        new OpenAiApi.Usage.PromptTokensDetails(null, cachedTokens),
                        null));
    }
}
