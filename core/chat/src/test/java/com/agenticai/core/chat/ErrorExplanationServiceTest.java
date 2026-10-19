package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.tenancy.PlatformFeatureBudgetService;
import com.agenticai.core.tenancy.PlatformFeatureBudgetService.BudgetDecision;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link ErrorExplanationService} is the first real consumer of the Smart AI
 * governor ({@link PlatformFeatureBudgetService} - audit item C3), so what
 * this suite locks in is the GOVERNANCE contract, not prompt quality: a
 * blocked budget means no LLM call at all (graceful static fallback), an
 * allowed call is always metered back via {@code recordSpend}, and a repeat
 * of an identical error is served from cache without spending again.
 *
 * <p>Mockito for the budget service and pool-row resolver (both concrete
 * Spring components with Redis/JPA collaborators - same reasoning as {@code
 * LlmGatewayBudgetServiceTest}); the actual model invocation is stubbed via
 * the package-private {@link ErrorExplanationService#invokeModel} seam (a
 * test subclass) rather than mocking {@code ChatClient}'s fluent chain.
 */
class ErrorExplanationServiceTest {

    private static final BudgetDecision GRANTED = new BudgetDecision(true, null);
    private static final BudgetDecision BLOCKED = new BudgetDecision(false, "feature-budget-exhausted");

    private PlatformFeatureBudgetService featureBudget;
    private PlatformHostedLlmProviderService hostedProviders;
    private HostedLlmChatClientFactory chatClientFactory;

    /** Subclass seam - counts model invocations and returns a canned reply (or throws). */
    private static final class StubbedService extends ErrorExplanationService {
        private final ErrorExplanationService.ModelReply reply;
        private final RuntimeException failure;
        int modelInvocations;

        StubbedService(
            PlatformFeatureBudgetService featureBudget, PlatformHostedLlmProviderService hostedProviders,
            HostedLlmChatClientFactory chatClientFactory, ErrorExplanationService.ModelReply reply,
            RuntimeException failure
        ) {
            super(featureBudget, hostedProviders, chatClientFactory, new LlmPricingCatalog());
            this.reply = reply;
            this.failure = failure;
        }

        @Override
        ModelReply invokeModel(String technicalDetail) {
            modelInvocations++;
            if (failure != null) {
                throw failure;
            }
            return reply;
        }
    }

    @BeforeEach
    void setUp() {
        featureBudget = mock(PlatformFeatureBudgetService.class);
        hostedProviders = mock(PlatformHostedLlmProviderService.class);
        chatClientFactory = mock(HostedLlmChatClientFactory.class);
    }

    private StubbedService serviceReturning(ErrorExplanationService.ModelReply reply) {
        return new StubbedService(featureBudget, hostedProviders, chatClientFactory, reply, null);
    }

    // --- budget denied -> static fallback, zero LLM calls ------------------

    @Test
    void blockedBudgetMeansNoModelCallAndNoSpend() {
        when(featureBudget.checkBudget(ErrorExplanationService.FEATURE_KEY)).thenReturn(BLOCKED);
        StubbedService service = serviceReturning(
            new ErrorExplanationService.ModelReply("should never be produced", "gemini-2.0-flash", 10L, 10L));

        Optional<String> explanation = service.explain(502, "connection reset by peer");

        assertTrue(explanation.isEmpty(), "a blocked budget must degrade to the caller's static fallback");
        assertEquals(0, service.modelInvocations, "the whole point of the gate: no LLM call when blocked");
        verify(featureBudget, never()).recordSpend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    // --- allowed -> explanation produced, spend recorded -------------------

    @Test
    void allowedCallRecordsPricingCatalogEstimateWhenUsageIsPresent() {
        when(featureBudget.checkBudget(ErrorExplanationService.FEATURE_KEY)).thenReturn(GRANTED);
        StubbedService service = serviceReturning(new ErrorExplanationService.ModelReply(
            "The provider connection dropped mid-request. Please try again.", "gemini-2.0-flash", 1000L, 100L));

        Optional<String> explanation = service.explain(502, "connection reset by peer");

        assertEquals("The provider connection dropped mid-request. Please try again.", explanation.orElseThrow());
        ArgumentCaptor<BigDecimal> cost = ArgumentCaptor.forClass(BigDecimal.class);
        verify(featureBudget).recordSpend(eq(ErrorExplanationService.FEATURE_KEY), cost.capture());
        // gemini-2.0-flash: 1000 * $0.10/M + 100 * $0.40/M = $0.000140 (LlmPricingCatalog).
        assertEquals(0, cost.getValue().compareTo(new BigDecimal("0.000140")),
            "spend must be the pricing catalog's estimate when token usage is available, got " + cost.getValue());
    }

    @Test
    void allowedCallRecordsConservativeFlatEstimateWhenUsageOrPricingIsUnavailable() {
        when(featureBudget.checkBudget(ErrorExplanationService.FEATURE_KEY)).thenReturn(GRANTED);
        // Unknown model id -> LlmPricingCatalog honestly returns null -> flat estimate.
        StubbedService service = serviceReturning(new ErrorExplanationService.ModelReply(
            "The provider is briefly unreachable. Try again shortly.", "some-unpriced-model", null, null));

        Optional<String> explanation = service.explain(502, "connection reset by peer");

        assertTrue(explanation.isPresent());
        verify(featureBudget).recordSpend(ErrorExplanationService.FEATURE_KEY, ErrorExplanationService.FLAT_SPEND_ESTIMATE_USD);
    }

    // --- cache -> identical errors never re-spend --------------------------

    @Test
    void identicalErrorSignatureIsServedFromCacheWithoutASecondCallOrSpend() {
        when(featureBudget.checkBudget(ErrorExplanationService.FEATURE_KEY)).thenReturn(GRANTED);
        StubbedService service = serviceReturning(new ErrorExplanationService.ModelReply(
            "The provider connection dropped mid-request. Please try again.", "gemini-2.0-flash", 1000L, 100L));

        Optional<String> first = service.explain(502, "connection reset by peer");
        Optional<String> second = service.explain(502, "connection reset by peer");

        assertEquals(first, second);
        assertEquals(1, service.modelInvocations, "an identical repeat error must be a cache hit, not a re-spend");
        verify(featureBudget, times(1)).recordSpend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cachedExplanationKeepsServingAfterBudgetIsExhausted() {
        // Already-paid-for answers stay useful even once the daily cap is hit -
        // the cache is checked BEFORE the budget gate on purpose.
        when(featureBudget.checkBudget(ErrorExplanationService.FEATURE_KEY)).thenReturn(GRANTED).thenReturn(BLOCKED);
        StubbedService service = serviceReturning(new ErrorExplanationService.ModelReply(
            "The provider connection dropped mid-request. Please try again.", "gemini-2.0-flash", 1000L, 100L));

        Optional<String> beforeExhaustion = service.explain(502, "connection reset by peer");
        Optional<String> afterExhaustion = service.explain(502, "connection reset by peer");

        assertTrue(beforeExhaustion.isPresent());
        assertEquals(beforeExhaustion, afterExhaustion);
        assertEquals(1, service.modelInvocations);
    }

    @Test
    void differentErrorDetailIsADifferentSignature() {
        when(featureBudget.checkBudget(ErrorExplanationService.FEATURE_KEY)).thenReturn(GRANTED);
        StubbedService service = serviceReturning(new ErrorExplanationService.ModelReply(
            "Something upstream failed. Please try again.", "gemini-2.0-flash", 1000L, 100L));

        service.explain(502, "connection reset by peer");
        service.explain(502, "SSL handshake failed");

        assertEquals(2, service.modelInvocations);
    }

    // --- degrade-gracefully edges ------------------------------------------

    @Test
    void modelFailureIsSwallowedWithNoSpendRecorded() {
        when(featureBudget.checkBudget(ErrorExplanationService.FEATURE_KEY)).thenReturn(GRANTED);
        StubbedService service = new StubbedService(
            featureBudget, hostedProviders, chatClientFactory, null, new IllegalStateException("pool call failed"));

        Optional<String> explanation = service.explain(502, "connection reset by peer");

        assertTrue(explanation.isEmpty(), "the explainer failing must never compound the original failure");
        verify(featureBudget, never()).recordSpend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unknownSentinelFromModelIsEmptyButStillMetered() {
        // The model honestly declining ("UNKNOWN") still consumed tokens - the
        // call is metered, the unusable answer is neither returned nor cached.
        when(featureBudget.checkBudget(ErrorExplanationService.FEATURE_KEY)).thenReturn(GRANTED);
        StubbedService service = serviceReturning(
            new ErrorExplanationService.ModelReply("UNKNOWN", "gemini-2.0-flash", 1000L, 5L));

        Optional<String> explanation = service.explain(502, "connection reset by peer");
        service.explain(502, "connection reset by peer");

        assertTrue(explanation.isEmpty());
        assertEquals(2, service.modelInvocations, "an unusable answer must not be cached");
        verify(featureBudget, times(2)).recordSpend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void blankDetailShortCircuitsBeforeTheBudgetGate() {
        StubbedService service = serviceReturning(
            new ErrorExplanationService.ModelReply("irrelevant", "gemini-2.0-flash", 1L, 1L));

        assertTrue(service.explain(502, null).isEmpty());
        assertTrue(service.explain(502, "   ").isEmpty());
        verify(featureBudget, never()).checkBudget(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void credentialShapedTextInTheModelsAnswerIsRedacted() {
        // The explanation reaches a tenant's screen - same redaction posture
        // as ProviderErrorTranslator's own messages.
        when(featureBudget.checkBudget(ErrorExplanationService.FEATURE_KEY)).thenReturn(GRANTED);
        StubbedService service = serviceReturning(new ErrorExplanationService.ModelReply(
            "The request to ?api_key=sk-SuperSecretLeak failed. Try again.", "gemini-2.0-flash", 1000L, 100L));

        String explanation = service.explain(502, "connection reset by peer").orElseThrow();

        assertTrue(explanation.contains("api_key=***"));
        assertFalse(explanation.contains("sk-SuperSecretLeak"));
    }
}
