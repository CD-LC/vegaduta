package com.agenticai.core.agents.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.budget.PerUserBudgetService.Modality;
import com.agenticai.core.security.TenantPlan;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * Regression coverage for the 2026-08-11 double-reconciliation fix on the
 * dollar-budget advisor - the exact mirror of {@link
 * FreeTierTokenTrackingAdvisorTest}'s coverage of the token-window advisor
 * (see {@link PerUserBudgetTrackingAdvisor}'s class javadoc for the full
 * write-up). The turn's ChatClient - this advisor attached as a default - is
 * reused for extra same-turn LLM calls (quality judge, consistency checker,
 * reflexion, task planning, toxicity guardrail, entity extraction), and
 * before the fix each of those re-ran {@link
 * PerUserBudgetService#reconcileSpendReservationForCall} with the SAME
 * captured {@code reservedMicroCents}, additively releasing the one dollar
 * reservation once per extra call. Only the instance's FIRST terminal
 * reconciliation may consume the reservation; every later one must forward
 * {@code reservedMicroCents=0}, which the service documents as "fall back to
 * plain additive {@code recordSpendForCall}".
 *
 * <p>Also pins the pre-existing terminal-signal semantics the fix must not
 * disturb: cancel releases (2026-08-09 leak fix), a failed link reconciles
 * nothing and does NOT consume the guard (no doOnError hook, deliberately).
 */
class PerUserBudgetTrackingAdvisorTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String USER_KEY = "user-1";
    private static final Modality MODALITY = Modality.TEXT;
    private static final String MODEL = "hosted-model-x";
    private static final long RESERVED_MICRO_CENTS = 150_000L;
    private static final TenantPlan PLAN = TenantPlan.FREE;

    /** A ChatClientResponse whose usage metadata reports prompt/completion
     * token counts - the shape this advisor reads real usage off of (it
     * forwards raw token counts; {@link PerUserBudgetService} owns pricing).
     * Explicit mocks per level rather than deep stubs so the test fails
     * loudly if the advisor's null-safe metadata walk ever changes shape. */
    private static ChatClientResponse responseWithTokenUsage(int promptTokens, int completionTokens) {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(promptTokens);
        when(usage.getCompletionTokens()).thenReturn(completionTokens);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);
        ChatResponse chatResponse = mock(ChatResponse.class);
        when(chatResponse.getMetadata()).thenReturn(metadata);
        ChatClientResponse response = mock(ChatClientResponse.class);
        when(response.chatResponse()).thenReturn(chatResponse);
        return response;
    }

    private static PerUserBudgetTrackingAdvisor advisorOver(PerUserBudgetService budgetService) {
        return new PerUserBudgetTrackingAdvisor(
            budgetService, TENANT_ID, USER_KEY, MODALITY, MODEL, RESERVED_MICRO_CENTS, PLAN);
    }

    @Test
    void firstCompletionReconcilesRealUsageAgainstTheReservation() {
        PerUserBudgetService budgetService = mock(PerUserBudgetService.class);
        PerUserBudgetTrackingAdvisor advisor = advisorOver(budgetService);

        // Built BEFORE the when() below - the helper stubs its own mocks, and
        // Mockito forbids interleaved stubbing inside a pending when().
        ChatClientResponse answer = responseWithTokenUsage(1200, 300);
        ChatClientRequest request = mock(ChatClientRequest.class);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(request)).thenReturn(Flux.just(answer));

        advisor.adviseStream(request, chain).subscribe();

        // The very first terminal reconciliation is unchanged by the fix: it
        // forwards the full captured reservation plus the merged Usage object
        // for the service to price and delta against it.
        ArgumentCaptor<Usage> usage = ArgumentCaptor.forClass(Usage.class);
        verify(budgetService).reconcileSpendReservationForUsage(
            eq(TENANT_ID), eq(USER_KEY), eq(MODALITY), eq(RESERVED_MICRO_CENTS), eq(MODEL), usage.capture(), eq(PLAN));
        assertEquals(1200, usage.getValue().getPromptTokens());
        assertEquals(300, usage.getValue().getCompletionTokens());
    }

    @Test
    void extraSameTurnCallAfterCompletionRecordsAdditivelyAndNeverReReleasesTheReservation() {
        PerUserBudgetService budgetService = mock(PerUserBudgetService.class);
        PerUserBudgetTrackingAdvisor advisor = advisorOver(budgetService);

        // Turn's user-facing answer streams to completion first...
        // (helper responses built before their when() - see note above)
        ChatClientResponse answer = responseWithTokenUsage(1200, 300);
        ChatClientRequest streamRequest = mock(ChatClientRequest.class);
        StreamAdvisorChain streamChain = mock(StreamAdvisorChain.class);
        when(streamChain.nextStream(streamRequest)).thenReturn(Flux.just(answer));
        advisor.adviseStream(streamRequest, streamChain).subscribe();

        // ...then the SAME winning-link ChatClient (same advisor instance) is
        // reused for an extra same-turn call - judge/checker/guardrail/
        // entity-extraction all take this .call() path.
        ChatClientResponse judgeAnswer = responseWithTokenUsage(400, 100);
        ChatClientRequest judgeRequest = mock(ChatClientRequest.class);
        CallAdvisorChain callChain = mock(CallAdvisorChain.class);
        when(callChain.nextCall(judgeRequest)).thenReturn(judgeAnswer);
        advisor.adviseCall(judgeRequest, callChain);

        // First reconciliation consumed the dollar reservation; the extra
        // call's real cost still lands, but through the reservedMicroCents=0
        // additive branch. Before the fix this second call re-passed the full
        // reservation, releasing it a second time as a phantom credit.
        ArgumentCaptor<Usage> usages = ArgumentCaptor.forClass(Usage.class);
        verify(budgetService, times(2)).reconcileSpendReservationForUsage(
            eq(TENANT_ID), eq(USER_KEY), eq(MODALITY), org.mockito.ArgumentMatchers.anyLong(), eq(MODEL),
            usages.capture(), eq(PLAN));
        assertEquals(1200, usages.getAllValues().get(0).getPromptTokens());
        assertEquals(300, usages.getAllValues().get(0).getCompletionTokens());
        assertEquals(400, usages.getAllValues().get(1).getPromptTokens());
        assertEquals(100, usages.getAllValues().get(1).getCompletionTokens());
        // The reservation must have been seen exactly once, ever.
        verify(budgetService, times(1)).reconcileSpendReservationForUsage(
            eq(TENANT_ID), eq(USER_KEY), eq(MODALITY), eq(RESERVED_MICRO_CENTS), eq(MODEL),
            nullable(Usage.class), eq(PLAN));
    }

    @Test
    void cancelThenExtraCallDoesNotReleaseTheReservationTwice() {
        PerUserBudgetService budgetService = mock(PerUserBudgetService.class);
        PerUserBudgetTrackingAdvisor advisor = advisorOver(budgetService);

        // Client aborts mid-stream: the cancel hook releases the full
        // reservation (2026-08-09 leak fix; null tokens = nothing observed,
        // which the service treats as a full release) and consumes the guard.
        ChatClientRequest streamRequest = mock(ChatClientRequest.class);
        StreamAdvisorChain streamChain = mock(StreamAdvisorChain.class);
        when(streamChain.nextStream(streamRequest)).thenReturn(Flux.never());
        Disposable subscription = advisor.adviseStream(streamRequest, streamChain).subscribe();
        subscription.dispose();

        // A straggler same-turn call still lands after the abort (e.g. an
        // async judge sample already in flight) - its real cost must record
        // additively, never re-releasing the already-released reservation.
        ChatClientResponse lateAnswer = responseWithTokenUsage(400, 100);
        ChatClientRequest lateRequest = mock(ChatClientRequest.class);
        CallAdvisorChain callChain = mock(CallAdvisorChain.class);
        when(callChain.nextCall(lateRequest)).thenReturn(lateAnswer);
        advisor.adviseCall(lateRequest, callChain);

        verify(budgetService).reconcileSpendReservationForUsage(
            eq(TENANT_ID), eq(USER_KEY), eq(MODALITY), eq(RESERVED_MICRO_CENTS), eq(MODEL),
            isNull(), eq(PLAN));
        ArgumentCaptor<Usage> lateUsage = ArgumentCaptor.forClass(Usage.class);
        verify(budgetService).reconcileSpendReservationForUsage(
            eq(TENANT_ID), eq(USER_KEY), eq(MODALITY), eq(0L), eq(MODEL), lateUsage.capture(), eq(PLAN));
        assertEquals(400, lateUsage.getValue().getPromptTokens());
        assertEquals(100, lateUsage.getValue().getCompletionTokens());
        verify(budgetService, times(1)).reconcileSpendReservationForUsage(
            eq(TENANT_ID), eq(USER_KEY), eq(MODALITY), eq(RESERVED_MICRO_CENTS), eq(MODEL),
            nullable(Usage.class), eq(PLAN));
    }

    @Test
    void idleTimeoutCancelOnOneLinkThenSuccessOnASiblingReleasesTheReservationOnlyOnce() {
        // 2026-08-12 guard-scope fix: the two sibling link instances share the
        // turn's ONE TurnReservationGuards, exactly as
        // FreeTierChatClientService#buildChainForFreeTenant wires them.
        PerUserBudgetService budgetService = mock(PerUserBudgetService.class);
        TurnReservationGuards guards = new TurnReservationGuards();
        PerUserBudgetTrackingAdvisor link1 = new PerUserBudgetTrackingAdvisor(
            budgetService, TENANT_ID, USER_KEY, MODALITY, MODEL, RESERVED_MICRO_CENTS, PLAN, guards);
        PerUserBudgetTrackingAdvisor link2 = new PerUserBudgetTrackingAdvisor(
            budgetService, TENANT_ID, USER_KEY, MODALITY, "sibling-model", RESERVED_MICRO_CENTS, PLAN, guards);

        // Link 1's stream idles until AgentChatService's Flux.timeout CANCELS
        // it (before surfacing a retryable 408 that fails the chain over) -
        // the cancel hook releases the full reservation and must consume the
        // TURN's guard, not just this instance's.
        ChatClientRequest timedOutRequest = mock(ChatClientRequest.class);
        StreamAdvisorChain timedOutChain = mock(StreamAdvisorChain.class);
        when(timedOutChain.nextStream(timedOutRequest)).thenReturn(Flux.never());
        Disposable subscription = link1.adviseStream(timedOutRequest, timedOutChain).subscribe();
        subscription.dispose();

        // The sibling link then succeeds. Before the fix its own private
        // guard was untouched, so it reconciled the SAME reservedMicroCents
        // again - crediting the user the full reservation per
        // timeout-then-success event. It must now record additively.
        ChatClientResponse retryAnswer = responseWithTokenUsage(1200, 300);
        ChatClientRequest retryRequest = mock(ChatClientRequest.class);
        StreamAdvisorChain retryChain = mock(StreamAdvisorChain.class);
        when(retryChain.nextStream(retryRequest)).thenReturn(Flux.just(retryAnswer));
        link2.adviseStream(retryRequest, retryChain).subscribe();

        verify(budgetService).reconcileSpendReservationForUsage(
            eq(TENANT_ID), eq(USER_KEY), eq(MODALITY), eq(RESERVED_MICRO_CENTS), eq(MODEL),
            isNull(), eq(PLAN));
        ArgumentCaptor<Usage> siblingUsage = ArgumentCaptor.forClass(Usage.class);
        verify(budgetService).reconcileSpendReservationForUsage(
            eq(TENANT_ID), eq(USER_KEY), eq(MODALITY), eq(0L), eq("sibling-model"), siblingUsage.capture(), eq(PLAN));
        assertEquals(1200, siblingUsage.getValue().getPromptTokens());
        assertEquals(300, siblingUsage.getValue().getCompletionTokens());
        // The turn's one reservation must have been seen exactly once, ever,
        // across BOTH sibling instances.
        verify(budgetService, times(1)).reconcileSpendReservationForUsage(
            eq(TENANT_ID), eq(USER_KEY), eq(MODALITY), eq(RESERVED_MICRO_CENTS),
            org.mockito.ArgumentMatchers.anyString(),
            nullable(Usage.class),
            eq(PLAN));
    }

    @Test
    void failedStreamReconcilesNothingAndDoesNotConsumeTheGuard() {
        PerUserBudgetService budgetService = mock(PerUserBudgetService.class);
        PerUserBudgetTrackingAdvisor advisor = advisorOver(budgetService);

        // A link-level error reconciles nothing (no doOnError, deliberately -
        // the chain may retry on a sibling link) and must NOT flip the
        // once-guard: the error never reaches reconcile() at all.
        ChatClientRequest failingRequest = mock(ChatClientRequest.class);
        StreamAdvisorChain failingChain = mock(StreamAdvisorChain.class);
        when(failingChain.nextStream(failingRequest)).thenReturn(Flux.error(new RuntimeException("provider blew up")));
        advisor.adviseStream(failingRequest, failingChain).subscribe(v -> { }, err -> { });

        verify(budgetService, never()).reconcileSpendReservationForUsage(
            eq(TENANT_ID), eq(USER_KEY), eq(MODALITY), org.mockito.ArgumentMatchers.anyLong(), eq(MODEL),
            nullable(Usage.class), eq(PLAN));

        // A later success on the SAME instance is then its first actual
        // reconciliation and must still see the untouched reservation.
        ChatClientResponse retryAnswer = responseWithTokenUsage(1200, 300);
        ChatClientRequest retryRequest = mock(ChatClientRequest.class);
        StreamAdvisorChain retryChain = mock(StreamAdvisorChain.class);
        when(retryChain.nextStream(retryRequest)).thenReturn(Flux.just(retryAnswer));
        advisor.adviseStream(retryRequest, retryChain).subscribe();

        ArgumentCaptor<Usage> retryUsage = ArgumentCaptor.forClass(Usage.class);
        verify(budgetService).reconcileSpendReservationForUsage(
            eq(TENANT_ID), eq(USER_KEY), eq(MODALITY), eq(RESERVED_MICRO_CENTS), eq(MODEL), retryUsage.capture(), eq(PLAN));
        assertEquals(1200, retryUsage.getValue().getPromptTokens());
        assertEquals(300, retryUsage.getValue().getCompletionTokens());
    }

    // ---- 2026-10-05 merged-stream-usage fix --------------------------------

    /** A single response whose usage reports only the given prompt/completion
     * tokens - one chunk of the split-usage shape (Anthropic reports input on
     * {@code message_start}, output on {@code message_delta}). */
    private static ChatClientResponse chunkWithUsage(Integer promptTokens, Integer completionTokens) {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(promptTokens);
        when(usage.getCompletionTokens()).thenReturn(completionTokens);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);
        ChatResponse chatResponse = mock(ChatResponse.class);
        when(chatResponse.getMetadata()).thenReturn(metadata);
        ChatClientResponse response = mock(ChatClientResponse.class);
        when(response.chatResponse()).thenReturn(chatResponse);
        return response;
    }

    @Test
    void streamedReconcileUsesMergedUsageAcrossChunksNotOnlyTheLastOne() {
        PerUserBudgetService budgetService = mock(PerUserBudgetService.class);
        PerUserBudgetTrackingAdvisor advisor = advisorOver(budgetService);

        // Anthropic's real stream shape: input tokens on the first chunk,
        // output tokens on the last. Reading only the last chunk (the old
        // bug) reconciled 300 completion tokens and dropped the 1200 input.
        // Chunks built BEFORE the when() - each helper stubs its own mocks,
        // and Mockito forbids interleaved stubbing inside a pending when().
        ChatClientResponse inputChunk = chunkWithUsage(1200, null);
        ChatClientResponse outputChunk = chunkWithUsage(null, 300);
        ChatClientRequest request = mock(ChatClientRequest.class);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(request)).thenReturn(Flux.just(inputChunk, outputChunk));

        advisor.adviseStream(request, chain).subscribe();

        ArgumentCaptor<Usage> usage = ArgumentCaptor.forClass(Usage.class);
        verify(budgetService).reconcileSpendReservationForUsage(
            eq(TENANT_ID), eq(USER_KEY), eq(MODALITY), eq(RESERVED_MICRO_CENTS), eq(MODEL), usage.capture(), eq(PLAN));
        assertEquals(1200, usage.getValue().getPromptTokens());
        assertEquals(300, usage.getValue().getCompletionTokens());
    }

    @Test
    void zeroCostTurnReleasesTheReservationOnceAndRecordsNoSpend() {
        PerUserBudgetService budgetService = mock(PerUserBudgetService.class);
        TurnReservationGuards guards = new TurnReservationGuards();
        PerUserBudgetTrackingAdvisor advisor = new PerUserBudgetTrackingAdvisor(
            budgetService, TENANT_ID, USER_KEY, MODALITY, MODEL, RESERVED_MICRO_CENTS, PLAN, guards, true);

        ChatClientResponse answer = responseWithTokenUsage(1200, 300);
        ChatClientRequest request = mock(ChatClientRequest.class);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(request)).thenReturn(Flux.just(answer));

        advisor.adviseStream(request, chain).subscribe();
        advisor.adviseStream(request, chain).subscribe();

        verify(budgetService, org.mockito.Mockito.times(1))
            .releaseSpendReservation(TENANT_ID, USER_KEY, MODALITY, RESERVED_MICRO_CENTS, PLAN);
        verify(budgetService, org.mockito.Mockito.never())
            .reconcileSpendReservationForUsage(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(budgetService, org.mockito.Mockito.never())
            .recordSpendForUsage(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
