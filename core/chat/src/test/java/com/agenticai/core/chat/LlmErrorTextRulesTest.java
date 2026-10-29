package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.chat.LlmCallDiagnosisService.DiagnosisResult;
import com.agenticai.core.providers.ProviderName;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The deterministic half of {@link LlmCallDiagnosisService}: every category a failed {@code
 * llm_call_log} row can land in before the AI fallback is even consulted, in the exact precedence
 * order {@link LlmErrorTextRules} checks them (tool-execution first - an MCP/tool failure must
 * never be mis-filed as a provider problem just because its own error text happens to contain an
 * HTTP status or the word "timeout").
 */
class LlmErrorTextRulesTest {

    private static LlmCallLog row(ProviderName provider, String model) {
        return new LlmCallLog(
                UUID.randomUUID(),
                "session-1",
                provider,
                model,
                10L,
                5L,
                15L,
                new BigDecimal("0.001"),
                false,
                "err",
                "req-1",
                "RuntimeException",
                "stack",
                1234L,
                Instant.now(),
                null,
                "trace",
                "span",
                null,
                null);
    }

    private static String categoryOf(String text) {
        DiagnosisResult result =
                LlmErrorTextRules.classify(text, row(ProviderName.OPENAI, "gpt-4o"));
        return result == null ? null : result.category();
    }

    @Test
    void aToolExecutionErrorWinsOverEveryProviderShapedWordInItsOwnText() {
        // The 2026-08-09 Playwright case: a tool failure whose underlying text carries "500"
        // or "timeout" is still a TOOL_EXECUTION_ERROR, checked before every provider pattern.
        DiagnosisResult result =
                LlmErrorTextRules.classify(
                        "Error calling tool: TextContent[browser_navigate failed: timeout waiting for 500ms]",
                        row(ProviderName.OPENAI, "gpt-4o"));

        assertEquals(LlmCallDiagnosisService.CATEGORY_TOOL_EXECUTION_ERROR, result.category());
        assertEquals(LlmCallDiagnosisService.SOURCE_RULES, result.source());
        assertTrue(result.suggestedModels().isEmpty());
    }

    @Test
    void mcpAndToolExecutionSpellingsAreAllToolFailures() {
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_TOOL_EXECUTION_ERROR,
                categoryOf("MCP server error: connection closed"));
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_TOOL_EXECUTION_ERROR,
                categoryOf("MCP tool error while running lookup"));
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_TOOL_EXECUTION_ERROR,
                categoryOf("tool execution failed: ENOENT chromium"));
    }

    @Test
    void penaltyParametersAreConclusiveOnTheirOwn() {
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_UNSUPPORTED_PARAM,
                categoryOf(
                        "Unsupported parameter: presence_penalty is not supported with this model"));
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_UNSUPPORTED_PARAM,
                categoryOf("frequency_penalty is not allowed"));
    }

    @Test
    void aBareStopWordNeedsAnUnsupportedHintOrAKnownRejectingModel() {
        // "stop" in prose alone must not classify - it is far too common a word.
        assertNull(categoryOf("please stop calling this endpoint"));
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_UNSUPPORTED_PARAM,
                categoryOf("parameter 'stop' is not supported"));
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_UNSUPPORTED_PARAM,
                categoryOf("stop sequences unsupported by this deployment"));
    }

    @Test
    void grokMetaAndReasoningModelsCountAsStopRejectingContext() {
        // No hint words at all - the provider/model family alone is the tell (Meta Model API
        // 400s on any request carrying stop, per the rule's javadoc).
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_UNSUPPORTED_PARAM,
                LlmErrorTextRules.classify(
                                "bad request involving stop", row(ProviderName.GROK, "grok-4"))
                        .category());
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_UNSUPPORTED_PARAM,
                LlmErrorTextRules.classify(
                                "bad request involving stop", row(ProviderName.META, "llama-4"))
                        .category());
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_UNSUPPORTED_PARAM,
                LlmErrorTextRules.classify(
                                "bad request involving stop",
                                row(ProviderName.OPENAI, "o3-reasoning"))
                        .category());
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_UNSUPPORTED_PARAM,
                LlmErrorTextRules.classify(
                                "bad request involving stop",
                                row(ProviderName.OPENAI, "muse-spark-1"))
                        .category());
        // An ordinary model + no hint: not an unsupported-param diagnosis.
        assertNull(
                LlmErrorTextRules.classify(
                        "bad request involving stop", row(ProviderName.OPENAI, "gpt-4o")));
    }

    @Test
    void authPhrasesAndStatusesAreCredentialFailures() {
        assertEquals(LlmCallDiagnosisService.CATEGORY_AUTH, categoryOf("HTTP 401 Unauthorized"));
        assertEquals(LlmCallDiagnosisService.CATEGORY_AUTH, categoryOf("invalid api key supplied"));
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_AUTH,
                categoryOf("403 forbidden: permission denied"));
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_AUTH,
                categoryOf("API key not valid. Please pass a valid API key."));
    }

    @Test
    void quotaIsCheckedBeforeRateLimitBecauseAnExhaustedBalanceOftenArrivesAs429() {
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_QUOTA_BILLING,
                categoryOf("429 insufficient_quota: You exceeded your current quota"));
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_QUOTA_BILLING,
                categoryOf("billing hard limit reached"));
    }

    @Test
    void rateLimitNeedsARealRateLimitSignal() {
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_RATE_LIMIT, categoryOf("429 Too Many Requests"));
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_RATE_LIMIT, categoryOf("rate_limit_exceeded"));
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_RATE_LIMIT,
                categoryOf("rate-limit reached, slow down"));
    }

    @Test
    void malformedRequestShapes() {
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_MALFORMED_REQUEST,
                categoryOf("400 Bad Request: messages must have at least one content part"));
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_MALFORMED_REQUEST,
                categoryOf("invalid request: field 'n' is not recognized"));
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_MALFORMED_REQUEST,
                categoryOf("malformed JSON in request body"));
    }

    @Test
    void serverSideStatusesAreProviderOutages() {
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_PROVIDER_OUTAGE,
                categoryOf("503 service unavailable"));
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_PROVIDER_OUTAGE,
                categoryOf("The model is overloaded"));
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_PROVIDER_OUTAGE, categoryOf("502 bad gateway"));
        assertEquals(
                LlmCallDiagnosisService.CATEGORY_PROVIDER_OUTAGE,
                categoryOf("internal server error"));
    }

    @Test
    void timeoutIsTheLastResortBeforeTheAiFallback() {
        DiagnosisResult result =
                LlmErrorTextRules.classify(
                        "request timed out after 30s", row(ProviderName.OPENAI, "gpt-4o"));
        assertEquals(LlmCallDiagnosisService.CATEGORY_TIMEOUT, result.category());
        // The title carries the row's own latency - part of the user-facing sentence.
        assertTrue(result.title().contains("1234 ms"));

        assertNull(
                categoryOf("xyzzy: a failure no rule recognizes"),
                "unrecognized text is the AI fallback's cue, not a wrong guess");
    }

    @Test
    void wordBoundariesKeepOrdinaryNumbersFromBecomingStatusCodes() {
        // "1401ms" must not read as a 401, "1500 requests" not as a 500.
        assertNull(categoryOf("failed after 1401ms of waiting"));
        assertNull(categoryOf("client sent 15004 requests in the window"));
    }
}
