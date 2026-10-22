package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.GenerateContentResponseUsageMetadata;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * Per-provider usage shapes for reasoning tokens, built from the real Spring AI 1.1.7 / Google
 * GenAI types (not stubs) so a dependency bump that reshapes one breaks here instead of silently
 * mis-billing.
 */
class ReasoningTokenAccountingTest {

    private final LlmPricingCatalog catalog = new LlmPricingCatalog();

    @Test
    void geminiThoughtsAreAdditionalToCompletionTokensAndBillable() {
        GoogleGenAiUsage usage =
                GoogleGenAiUsage.from(
                        GenerateContentResponseUsageMetadata.builder()
                                .promptTokenCount(1000)
                                .candidatesTokenCount(200)
                                .thoughtsTokenCount(800)
                                .totalTokenCount(2000)
                                .build());

        ReasoningTokenAccounting accounting = ReasoningTokenAccounting.from(usage);

        assertTrue(accounting.isPresent());
        assertFalse(accounting.includedInCompletionTokens());
        assertEquals(800L, accounting.reasoningTokens());
        assertEquals(800L, accounting.billableReasoningTokens());
    }

    @Test
    void geminiWithoutThinkingIsAbsentNotZero() {
        GoogleGenAiUsage usage =
                GoogleGenAiUsage.from(
                        GenerateContentResponseUsageMetadata.builder()
                                .promptTokenCount(10)
                                .candidatesTokenCount(5)
                                .totalTokenCount(15)
                                .build());
        // GoogleGenAiUsage.from defaults a missing count to null (Optional.orElse(null)) or 0
        // depending on SDK;
        // either way nothing billable may be produced.
        Long billable = ReasoningTokenAccounting.from(usage).billableReasoningTokens();
        assertTrue(billable == null || billable == 0L);
    }

    @Test
    void openAiReasoningTokensAreInsideCompletionTokensAndNeverBilledTwice() {
        OpenAiApi.Usage nativeUsage =
                new OpenAiApi.Usage(
                        500,
                        100,
                        600,
                        null,
                        new OpenAiApi.Usage.CompletionTokenDetails(400, null, null, null));
        Usage usage = new DefaultUsage(100, 500, 600, nativeUsage);

        ReasoningTokenAccounting accounting = ReasoningTokenAccounting.from(usage);

        assertTrue(accounting.isPresent());
        assertTrue(accounting.includedInCompletionTokens());
        assertEquals(400L, accounting.reasoningTokens(), "kept for observability");
        assertNull(
                accounting.billableReasoningTokens(),
                "already inside completionTokens - double-billing guard");
    }

    @Test
    void anthropicReportsNoSeparateReasoningBucket() {
        Usage usage = new DefaultUsage(100, 500, 600, new AnthropicApi.Usage(100, 500, 0, 0));
        assertEquals(ReasoningTokenAccounting.ABSENT, ReasoningTokenAccounting.from(usage));
    }

    @Test
    void nullAndUnknownUsageAreAbsent() {
        assertEquals(ReasoningTokenAccounting.ABSENT, ReasoningTokenAccounting.from(null));
        assertEquals(
                ReasoningTokenAccounting.ABSENT,
                ReasoningTokenAccounting.from(new DefaultUsage(1, 2, 3)));
    }

    // --- pricing ------------------------------------------------------------

    @Test
    void pricingAddsReasoningTokensAtTheOutputRate() {
        // claude-sonnet-5 standard: $3 in / $15 out per MTok (see LlmPricingCatalogTest).
        BigDecimal without =
                catalog.estimateCostUsd(
                        "GEMINI", "claude-sonnet-5", 1_000_000L, 1_000_000L, null, null, null);
        BigDecimal with =
                catalog.estimateCostUsd(
                        "GEMINI",
                        "claude-sonnet-5",
                        1_000_000L,
                        1_000_000L,
                        null,
                        null,
                        1_000_000L);

        assertEquals(
                0,
                with.subtract(without).compareTo(new BigDecimal("15")),
                "1M reasoning tokens must add exactly one output-rate MTok");
    }

    @Test
    void pricingIgnoresNullOrZeroReasoningTokensAndMatchesTheSixArgOverload() {
        BigDecimal base =
                catalog.estimateCostUsd("OPENAI", "claude-sonnet-5", 1000L, 2000L, null, null);
        assertEquals(
                0,
                base.compareTo(
                        catalog.estimateCostUsd(
                                "OPENAI", "claude-sonnet-5", 1000L, 2000L, null, null, null)));
        assertEquals(
                0,
                base.compareTo(
                        catalog.estimateCostUsd(
                                "OPENAI", "claude-sonnet-5", 1000L, 2000L, null, null, 0L)));
    }

    @Test
    void endToEndGeminiThinkingCallCostsMoreThanTheSameCallWithoutThoughts() {
        GoogleGenAiUsage usage =
                GoogleGenAiUsage.from(
                        GenerateContentResponseUsageMetadata.builder()
                                .promptTokenCount(1000)
                                .candidatesTokenCount(200)
                                .thoughtsTokenCount(800)
                                .totalTokenCount(2000)
                                .build());
        ReasoningTokenAccounting reasoning = ReasoningTokenAccounting.from(usage);
        long prompt = usage.getPromptTokens();
        long completion = usage.getCompletionTokens();

        BigDecimal naive =
                catalog.estimateCostUsd(
                        "GEMINI", "claude-sonnet-5", prompt, completion, null, null, null);
        BigDecimal fixed =
                catalog.estimateCostUsd(
                        "GEMINI",
                        "claude-sonnet-5",
                        prompt,
                        completion,
                        null,
                        null,
                        reasoning.billableReasoningTokens());

        assertTrue(fixed.compareTo(naive) > 0);
        // 800 extra output tokens at $15/MTok = $0.012
        assertEquals(0, fixed.subtract(naive).compareTo(new BigDecimal("0.012")));
    }

    @Test
    void openAiCompatEndpointThatExcludesThoughtsFromCompletionIsBilledOnTop() {
        // completion 100, reasoning 400, total 1000 = prompt 500 + completion 100 + reasoning 400:
        // the usage block itself proves the thoughts sit OUTSIDE completion (Gemini OpenAI-compat
        // shape).
        OpenAiApi.Usage nativeUsage =
                new OpenAiApi.Usage(
                        100,
                        500,
                        1000,
                        null,
                        new OpenAiApi.Usage.CompletionTokenDetails(400, null, null, null));
        Usage usage = new DefaultUsage(500, 100, 1000, nativeUsage);

        ReasoningTokenAccounting accounting = ReasoningTokenAccounting.from(usage);

        assertFalse(accounting.includedInCompletionTokens());
        assertEquals(400L, accounting.billableReasoningTokens());
    }

    @Test
    void openAiWithNoUsableTotalKeepsTheSubsetRule() {
        OpenAiApi.Usage nativeUsage =
                new OpenAiApi.Usage(
                        100,
                        500,
                        null,
                        null,
                        new OpenAiApi.Usage.CompletionTokenDetails(400, null, null, null));
        assertNull(
                ReasoningTokenAccounting.from(new DefaultUsage(500, 100, null, nativeUsage))
                        .billableReasoningTokens());
    }

    @Test
    void anthropicThinkingCallIsPricedThroughOutputTokensNeverUnderPriced() {
        // 5000 output tokens, of which most are thinking: Anthropic bills all of them at the output
        // rate and the API reports no separate bucket, so the output count IS the
        // thinking-inclusive count.
        Usage usage = new DefaultUsage(1000, 5000, 6000, new AnthropicApi.Usage(1000, 5000, 0, 0));
        ReasoningTokenAccounting accounting = ReasoningTokenAccounting.from(usage);
        assertNull(accounting.billableReasoningTokens());

        BigDecimal cost =
                catalog.estimateCostUsd(
                        "ANTHROPIC",
                        "claude-sonnet-5",
                        1000L,
                        5000L,
                        null,
                        null,
                        accounting.billableReasoningTokens());
        // $3/MTok in, $15/MTok out: 1000*3/1e6 + 5000*15/1e6 = 0.003 + 0.075
        assertEquals(0, cost.compareTo(new BigDecimal("0.078")));
    }
}
