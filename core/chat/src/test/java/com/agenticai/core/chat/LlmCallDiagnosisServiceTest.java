package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.LlmCallDiagnosisService.DiagnosisResult;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderModelResponse;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.tenancy.PlatformFeatureBudgetService;
import com.agenticai.core.tenancy.PlatformFeatureBudgetService.BudgetDecision;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What this suite locks in mirrors {@link ErrorExplanationServiceTest}'s
 * governance focus, extended by the rules-first contract that makes {@link
 * LlmCallDiagnosisService} cheap: every failure a deterministic rule can
 * recognize (retired model, catalog miss, auth, rate limit, unsupported
 * param...) must be diagnosed with ZERO LLM involvement - not even a budget
 * check - and only a genuinely unrecognized error may reach the budget gate,
 * where a blocked budget degrades to a rules-built UNKNOWN, never an error.
 *
 * <p>Mockito for the catalog/budget/pool collaborators (concrete Spring
 * components with JPA/Redis dependencies); the actual model invocation is
 * stubbed via the package-private {@link
 * LlmCallDiagnosisService#invokeModel} seam (a test subclass), same pattern
 * as {@code ErrorExplanationServiceTest}.
 */
class LlmCallDiagnosisServiceTest {

    private static final BudgetDecision GRANTED = new BudgetDecision(true, null);
    private static final BudgetDecision BLOCKED = new BudgetDecision(false, "feature-budget-exhausted");

    private LlmCallLogRepository repository;
    private ProviderModelCatalogService catalogService;
    private PlatformFeatureBudgetService featureBudget;
    private PlatformHostedLlmProviderService hostedProviders;
    private HostedLlmChatClientFactory chatClientFactory;

    /** Subclass seam - counts model invocations and returns a canned reply (or throws). */
    private final class StubbedService extends LlmCallDiagnosisService {
        private final LlmCallDiagnosisService.ModelReply reply;
        private final RuntimeException failure;
        int modelInvocations;

        StubbedService(LlmCallDiagnosisService.ModelReply reply, RuntimeException failure) {
            super(repository, catalogService, featureBudget, hostedProviders, chatClientFactory, new LlmPricingCatalog());
            this.reply = reply;
            this.failure = failure;
        }

        @Override
        ModelReply invokeModel(String userPrompt) {
            modelInvocations++;
            if (failure != null) {
                throw failure;
            }
            return reply;
        }
    }

    @BeforeEach
    void setUp() {
        repository = mock(LlmCallLogRepository.class);
        catalogService = mock(ProviderModelCatalogService.class);
        featureBudget = mock(PlatformFeatureBudgetService.class);
        hostedProviders = mock(PlatformHostedLlmProviderService.class);
        chatClientFactory = mock(HostedLlmChatClientFactory.class);
    }

    private StubbedService serviceReturning(String modelText) {
        return new StubbedService(
            new LlmCallDiagnosisService.ModelReply(modelText, "gemini-2.0-flash", 1000L, 100L), null);
    }

    private static LlmCallLog failedCall(ProviderName provider, String model, String errorMessage, String exceptionType) {
        return new LlmCallLog(
            null, null, provider, model, null, null, null, null,
            false, errorMessage, null, exceptionType, null, 1200L, Instant.now(), null, null, null, null, null, null);
    }

    private static ProviderModelResponse catalogModel(String modelId) {
        return new ProviderModelResponse(modelId, modelId, 128_000, false, false, false, "Text in, text out.");
    }

    // --- rules layer: zero LLM involvement ---------------------------------

    @Test
    void retiredModelIsDiagnosedByRulesWithReplacementFirstAndNoBudgetCheck() {
        when(catalogService.listModels(ProviderName.GROK))
            .thenReturn(List.of(catalogModel("grok-4.5"), catalogModel("grok-4.3"), catalogModel("grok-3")));
        StubbedService service = serviceReturning("should never be produced");

        DiagnosisResult result = service.diagnose(failedCall(
            ProviderName.GROK, "grok-3",
            "404 The model grok-3 does not exist or your team has no access to it", null));

        assertEquals(LlmCallDiagnosisService.CATEGORY_RETIRED_MODEL, result.category());
        assertEquals(LlmCallDiagnosisService.SOURCE_RULES, result.source());
        assertEquals("grok-4.3", result.suggestedModels().get(0),
            "the known replacement must lead the suggestions");
        assertFalse(result.suggestedModels().contains("grok-3"),
            "the failing (retired) model must never be suggested back");
        assertEquals(0, service.modelInvocations);
        verify(featureBudget, never()).checkBudget(anyString());
    }

    @Test
    void modelAbsentFromTheProviderCatalogIsUnknownModelWithCatalogSuggestions() {
        when(catalogService.listModels(ProviderName.GROK))
            .thenReturn(List.of(catalogModel("grok-4.5"), catalogModel("grok-4.3")));
        StubbedService service = serviceReturning("should never be produced");

        DiagnosisResult result = service.diagnose(failedCall(
            ProviderName.GROK, "grok-9000", "The model does not exist", null));

        assertEquals(LlmCallDiagnosisService.CATEGORY_UNKNOWN_MODEL, result.category());
        assertEquals(LlmCallDiagnosisService.SOURCE_RULES, result.source());
        assertTrue(result.suggestedModels().contains("grok-4.5"));
        assertEquals(0, service.modelInvocations);
        verify(featureBudget, never()).checkBudget(anyString());
    }

    @Test
    void unsupportedParameterErrorIsRecognizedByRules() {
        when(catalogService.listModels(ProviderName.GROK)).thenReturn(List.of(catalogModel("grok-4.5")));
        StubbedService service = serviceReturning("should never be produced");

        DiagnosisResult result = service.diagnose(failedCall(
            ProviderName.GROK, "grok-4.5",
            "400 Argument not supported on this model: presence_penalty", null));

        assertEquals(LlmCallDiagnosisService.CATEGORY_UNSUPPORTED_PARAM, result.category());
        assertEquals(LlmCallDiagnosisService.SOURCE_RULES, result.source());
        assertEquals(0, service.modelInvocations);
    }

    @Test
    void stopParameterRejectionOnMetaIsRecognizedByRulesEvenWithoutAnUnsupportedHint() {
        // Meta Model API 400s on "stop" (dev.meta.ai, 2026-09-17). The bare
        // word "stop" only counts in a known-to-reject-it context; META (and
        // a muse-spark id via OpenRouter) is now one of those contexts.
        when(catalogService.listModels(ProviderName.META)).thenReturn(List.of(catalogModel("muse-spark-1.3")));
        when(catalogService.listModels(ProviderName.OPENROUTER)).thenReturn(List.of(catalogModel("meta/muse-spark-1.3")));
        StubbedService service = serviceReturning("should never be produced");

        DiagnosisResult direct = service.diagnose(failedCall(
            ProviderName.META, "muse-spark-1.3", "400 Bad Request: unexpected parameter 'stop'", null));
        DiagnosisResult viaOpenRouter = service.diagnose(failedCall(
            ProviderName.OPENROUTER, "meta/muse-spark-1.3", "400 Bad Request: unexpected parameter 'stop'", null));

        assertEquals(LlmCallDiagnosisService.CATEGORY_UNSUPPORTED_PARAM, direct.category());
        assertEquals(LlmCallDiagnosisService.CATEGORY_UNSUPPORTED_PARAM, viaOpenRouter.category());
        assertEquals(LlmCallDiagnosisService.SOURCE_RULES, direct.source());
        assertEquals(0, service.modelInvocations);
    }

    @Test
    void authFailureIsRecognizedByRules() {
        when(catalogService.listModels(ProviderName.OPENAI)).thenReturn(List.of(catalogModel("gpt-4o")));
        StubbedService service = serviceReturning("should never be produced");

        DiagnosisResult result = service.diagnose(failedCall(
            ProviderName.OPENAI, "gpt-4o", "401 Incorrect API key provided", null));

        assertEquals(LlmCallDiagnosisService.CATEGORY_AUTH, result.category());
        assertEquals(LlmCallDiagnosisService.SOURCE_RULES, result.source());
        assertEquals(0, service.modelInvocations);
    }

    @Test
    void toolExecutionFailureIsRecognizedByRulesNotMisdiagnosedAsProviderOutage() {
        // Live incident, 2026-08-09: an agent's Playwright MCP tool call
        // failed with a missing-Chromium error; Spring AI's MCP tool
        // callback wraps that as "Error calling tool: [TextContent[...]]",
        // which shares no LLM-provider-failure vocabulary at all - before
        // this rule existed, this fell through to the AI fallback and got
        // mis-diagnosed as PROVIDER_OUTAGE ("retry the call"), which is
        // actively wrong: retrying calls the AI provider again, but the AI
        // provider was never the problem, and the missing-binary failure
        // would recur identically every time.
        StubbedService service = serviceReturning("should never be produced");

        DiagnosisResult result = service.diagnose(failedCall(
            ProviderName.GEMINI, "gemini-2.5-flash",
            "Error calling tool: [TextContent[annotations=null, text=### Error\n"
                + "Error: async initializeServer: Chromium distribution 'chrome' is not found at "
                + "/opt/google/chrome/chrome\nRun \"npx playwright install chrome\", meta=null]]",
            null));

        assertEquals(LlmCallDiagnosisService.CATEGORY_TOOL_EXECUTION_ERROR, result.category());
        assertFalse(LlmCallDiagnosisService.CATEGORY_PROVIDER_OUTAGE.equals(result.category()));
        assertEquals(LlmCallDiagnosisService.SOURCE_RULES, result.source());
        assertEquals(0, service.modelInvocations, "must never reach the AI fallback - this is a deterministic rule");
    }

    @Test
    void rateLimitIsRecognizedByRules() {
        when(catalogService.listModels(ProviderName.ANTHROPIC)).thenReturn(List.of(catalogModel("claude-sonnet-5")));
        StubbedService service = serviceReturning("should never be produced");

        DiagnosisResult result = service.diagnose(failedCall(
            ProviderName.ANTHROPIC, "claude-sonnet-5", "429 rate limit exceeded, retry later", null));

        assertEquals(LlmCallDiagnosisService.CATEGORY_RATE_LIMIT, result.category());
        assertEquals(0, service.modelInvocations);
    }

    @Test
    void quotaWinsOverRateLimitWhenBothSignalsArePresent() {
        // OpenAI's insufficient_quota arrives as HTTP 429 - "add credit" is
        // the actionable diagnosis there, not "slow down."
        when(catalogService.listModels(ProviderName.OPENAI)).thenReturn(List.of(catalogModel("gpt-4o")));
        StubbedService service = serviceReturning("should never be produced");

        DiagnosisResult result = service.diagnose(failedCall(
            ProviderName.OPENAI, "gpt-4o",
            "429 You exceeded your current quota, please check your plan and billing details", null));

        assertEquals(LlmCallDiagnosisService.CATEGORY_QUOTA_BILLING, result.category());
    }

    @Test
    void timeoutExceptionTypeAloneIsEnoughForTheTimeoutRule() {
        when(catalogService.listModels(ProviderName.ANTHROPIC)).thenReturn(List.of(catalogModel("claude-sonnet-5")));
        StubbedService service = serviceReturning("should never be produced");

        DiagnosisResult result = service.diagnose(failedCall(
            ProviderName.ANTHROPIC, "claude-sonnet-5", null, "java.net.SocketTimeoutException"));

        assertEquals(LlmCallDiagnosisService.CATEGORY_TIMEOUT, result.category());
        assertEquals(0, service.modelInvocations);
    }

    // --- AI fallback governance --------------------------------------------

    @Test
    void unknownErrorWithBlockedBudgetReturnsRulesUnknownAndNeverTouchesTheModel() {
        when(catalogService.listModels(ProviderName.ANTHROPIC)).thenReturn(List.of(catalogModel("claude-sonnet-5")));
        when(featureBudget.checkBudget(LlmCallDiagnosisService.FEATURE_KEY)).thenReturn(BLOCKED);
        StubbedService service = serviceReturning("should never be produced");

        DiagnosisResult result = service.diagnose(failedCall(
            ProviderName.ANTHROPIC, "claude-sonnet-5", "something inexplicable happened", null));

        assertEquals(LlmCallDiagnosisService.CATEGORY_UNKNOWN, result.category());
        assertEquals(LlmCallDiagnosisService.SOURCE_RULES, result.source());
        assertEquals(0, service.modelInvocations, "the whole point of the gate: no LLM call when blocked");
        verify(featureBudget, never()).recordSpend(anyString(), any());
    }

    @Test
    void aiFallbackParsesStrictJsonStripsCodeFencesAndRecordsSpend() {
        when(catalogService.listModels(ProviderName.ANTHROPIC)).thenReturn(List.of(catalogModel("claude-sonnet-5")));
        when(featureBudget.checkBudget(LlmCallDiagnosisService.FEATURE_KEY)).thenReturn(GRANTED);
        StubbedService service = serviceReturning("""
            ```json
            {"category": "auth", "title": "Key rejected", "explanation": "The key was refused.", "suggestedFix": "Re-enter the key."}
            ```""");

        DiagnosisResult result = service.diagnose(failedCall(
            ProviderName.ANTHROPIC, "claude-sonnet-5", "something inexplicable happened", null));

        assertEquals(LlmCallDiagnosisService.CATEGORY_AUTH, result.category(),
            "the category must be case-normalized into the closed set");
        assertEquals("Key rejected", result.title());
        assertEquals(LlmCallDiagnosisService.SOURCE_AI, result.source());
        assertTrue(result.suggestedModels().isEmpty());
        assertEquals(1, service.modelInvocations);
        verify(featureBudget).recordSpend(eq(LlmCallDiagnosisService.FEATURE_KEY), any());
    }

    @Test
    void aiReplyThatIsNotJsonDegradesToUnknownCarryingTheRawTextAndIsStillMetered() {
        when(catalogService.listModels(ProviderName.ANTHROPIC)).thenReturn(List.of(catalogModel("claude-sonnet-5")));
        when(featureBudget.checkBudget(LlmCallDiagnosisService.FEATURE_KEY)).thenReturn(GRANTED);
        StubbedService service = serviceReturning(
            "This looks like a transient networking problem between the platform and the provider.");

        DiagnosisResult result = service.diagnose(failedCall(
            ProviderName.ANTHROPIC, "claude-sonnet-5", "something inexplicable happened", null));

        assertEquals(LlmCallDiagnosisService.CATEGORY_UNKNOWN, result.category());
        assertEquals(LlmCallDiagnosisService.SOURCE_AI, result.source());
        assertTrue(result.explanation().contains("transient networking problem"),
            "paid-for prose must not be thrown away on a parse failure");
        verify(featureBudget).recordSpend(eq(LlmCallDiagnosisService.FEATURE_KEY), any());
    }

    @Test
    void aiInventedCategoryIsCoercedToUnknown() {
        when(catalogService.listModels(ProviderName.ANTHROPIC)).thenReturn(List.of(catalogModel("claude-sonnet-5")));
        when(featureBudget.checkBudget(LlmCallDiagnosisService.FEATURE_KEY)).thenReturn(GRANTED);
        StubbedService service = serviceReturning(
            "{\"category\": \"COSMIC_RAYS\", \"title\": \"t\", \"explanation\": \"e\", \"suggestedFix\": \"f\"}");

        DiagnosisResult result = service.diagnose(failedCall(
            ProviderName.ANTHROPIC, "claude-sonnet-5", "something inexplicable happened", null));

        assertEquals(LlmCallDiagnosisService.CATEGORY_UNKNOWN, result.category());
    }

    @Test
    void identicalUnknownErrorIsServedFromCacheWithoutASecondCallOrSpend() {
        when(catalogService.listModels(ProviderName.ANTHROPIC)).thenReturn(List.of(catalogModel("claude-sonnet-5")));
        when(featureBudget.checkBudget(LlmCallDiagnosisService.FEATURE_KEY)).thenReturn(GRANTED);
        StubbedService service = serviceReturning(
            "{\"category\": \"PROVIDER_OUTAGE\", \"title\": \"t\", \"explanation\": \"e\", \"suggestedFix\": \"f\"}");
        LlmCallLog row = failedCall(ProviderName.ANTHROPIC, "claude-sonnet-5", "something inexplicable happened", null);

        DiagnosisResult first = service.diagnose(row);
        DiagnosisResult second = service.diagnose(row);

        assertEquals(first, second);
        assertEquals(1, service.modelInvocations, "an identical repeat error must be a cache hit, not a re-spend");
        verify(featureBudget, times(1)).recordSpend(anyString(), any());
    }

    @Test
    void modelFailureDegradesToUnknownWithNoSpendRecorded() {
        when(catalogService.listModels(ProviderName.ANTHROPIC)).thenReturn(List.of(catalogModel("claude-sonnet-5")));
        when(featureBudget.checkBudget(LlmCallDiagnosisService.FEATURE_KEY)).thenReturn(GRANTED);
        StubbedService service = new StubbedService(null, new IllegalStateException("pool call failed"));

        DiagnosisResult result = service.diagnose(failedCall(
            ProviderName.ANTHROPIC, "claude-sonnet-5", "something inexplicable happened", null));

        assertEquals(LlmCallDiagnosisService.CATEGORY_UNKNOWN, result.category());
        assertEquals(LlmCallDiagnosisService.SOURCE_RULES, result.source(),
            "a broken diagnoser must fall back to the honest generic answer, never an error");
        verify(featureBudget, never()).recordSpend(anyString(), any());
    }

    @Test
    void credentialShapedTextInTheAiAnswerIsRedacted() {
        when(catalogService.listModels(ProviderName.ANTHROPIC)).thenReturn(List.of(catalogModel("claude-sonnet-5")));
        when(featureBudget.checkBudget(LlmCallDiagnosisService.FEATURE_KEY)).thenReturn(GRANTED);
        StubbedService service = serviceReturning(
            "{\"category\": \"AUTH\", \"title\": \"t\", "
                + "\"explanation\": \"The request to ?api_key=sk-SuperSecretLeak failed.\", \"suggestedFix\": \"f\"}");

        DiagnosisResult result = service.diagnose(failedCall(
            ProviderName.ANTHROPIC, "claude-sonnet-5", "something inexplicable happened", null));

        assertTrue(result.explanation().contains("api_key=***"));
        assertFalse(result.explanation().contains("sk-SuperSecretLeak"));
    }

    // --- diagnoseCall row loading ------------------------------------------

    @Test
    void diagnoseCallIsEmptyForAMissingRowAndForASuccessfulCall() {
        UUID missingId = UUID.randomUUID();
        UUID successId = UUID.randomUUID();
        LlmCallLog successRow = new LlmCallLog(
            null, null, ProviderName.ANTHROPIC, "claude-sonnet-5", 10L, 10L, 20L, null,
            true, null, null, null, null, 500L, Instant.now(), null, null, null, null, null, null);
        when(repository.findById(missingId)).thenReturn(Optional.empty());
        when(repository.findById(successId)).thenReturn(Optional.of(successRow));
        StubbedService service = serviceReturning("irrelevant");

        assertTrue(service.diagnoseCall(missingId).isEmpty(), "a missing row must map to the controller's 404");
        assertTrue(service.diagnoseCall(successId).isEmpty(), "a successful call has nothing to diagnose");
    }

    @Test
    void diagnoseCallDiagnosesAFailedRow() {
        UUID id = UUID.randomUUID();
        LlmCallLog failed = failedCall(ProviderName.OPENAI, "gpt-4o", "401 unauthorized", null);
        when(repository.findById(id)).thenReturn(Optional.of(failed));
        when(catalogService.listModels(ProviderName.OPENAI)).thenReturn(List.of(catalogModel("gpt-4o")));
        StubbedService service = serviceReturning("should never be produced");

        DiagnosisResult result = service.diagnoseCall(id).orElseThrow();

        assertEquals(LlmCallDiagnosisService.CATEGORY_AUTH, result.category());
    }
}
