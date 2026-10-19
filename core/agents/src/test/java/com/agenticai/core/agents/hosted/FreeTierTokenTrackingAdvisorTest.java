package com.agenticai.core.agents.hosted;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.quota.FreeTierUsageLimiterService;
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
 * Regression coverage for the 2026-08-09 leak fix - see {@link
 * FreeTierTokenTrackingAdvisor}'s class javadoc ("cancel is NOT the same
 * case as error"). Before this fix, a client disconnecting mid-stream
 * delivered a Reactor cancel signal that neither {@code doOnComplete} nor
 * (deliberately) {@code doOnError} ever saw, so {@link
 * FreeTierUsageLimiterService#reconcileTokenReservation} was never called -
 * the full {@code maxTokens + promptEstimate} reservation stayed charged
 * against the tenant's daily/monthly token window for the rest of that
 * window's TTL despite zero tokens ever being delivered.
 *
 * <p>Also covers the 2026-08-11 double-reconciliation fix (same class
 * javadoc): the turn's ChatClient - this advisor attached as a default - is
 * reused for extra same-turn calls (quality judge, consistency checker,
 * guardrails, entity extraction), and before the fix each of those re-ran
 * the reconciliation with the SAME captured reservation, additively
 * releasing it once per extra call (~9.5k phantom tokens credited per
 * ~500-token judge call against a ~10k reservation). Only the instance's
 * FIRST terminal reconciliation may consume the reservation; every later
 * one must record its real usage additively with reservation 0.
 */
class FreeTierTokenTrackingAdvisorTest {

    private static final String TENANT_ID = "tenant-1";
    private static final long RESERVED_TOKENS = 6000L;

    /** A ChatClientResponse whose usage metadata reports {@code totalTokens}
     * - the shape the advisor reads real usage off of. Explicit mocks per
     * level rather than deep stubs so the test fails loudly if the advisor's
     * null-safe metadata walk ever changes shape.
     *
     * <p>Since the 2026-10-05 fix the advisor merges usage per chunk through
     * {@code LlmMetricsAdvisor.StreamUsageAccumulator}, which keys off
     * prompt/completion - so this helper reports a realistic split
     * (three-quarters input) rather than a total alone, which the
     * accumulator would discard as usage-free. {@code totalTokens} is still
     * stubbed explicitly because {@code merged()} hands the chunk's own
     * Usage object back when it already carries both maxima. */
    private static ChatClientResponse responseWithTotalTokens(int totalTokens) {
        Usage usage = mock(Usage.class);
        int promptTokens = (totalTokens * 3) / 4;
        when(usage.getPromptTokens()).thenReturn(promptTokens);
        when(usage.getCompletionTokens()).thenReturn(totalTokens - promptTokens);
        when(usage.getTotalTokens()).thenReturn(totalTokens);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);
        ChatResponse chatResponse = mock(ChatResponse.class);
        when(chatResponse.getMetadata()).thenReturn(metadata);
        ChatClientResponse response = mock(ChatClientResponse.class);
        when(response.chatResponse()).thenReturn(chatResponse);
        return response;
    }

    @Test
    void cancellingTheStreamMidFlightReleasesTheFullReservation() {
        FreeTierUsageLimiterService usageLimiterService = mock(FreeTierUsageLimiterService.class);
        FreeTierTokenTrackingAdvisor advisor =
            new FreeTierTokenTrackingAdvisor(usageLimiterService, TENANT_ID, RESERVED_TOKENS);

        ChatClientRequest request = mock(ChatClientRequest.class);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        // Never emits and never completes - stands in for a provider stream
        // that's still going when the client disconnects.
        when(chain.nextStream(request)).thenReturn(Flux.never());

        Flux<ChatClientResponse> result = advisor.adviseStream(request, chain);
        Disposable subscription = result.subscribe();
        // Disposing an in-flight subscription delivers Reactor's cancel
        // signal to the upstream - exactly what happens server-side when a
        // browser aborts the fetch (e.g. the connect-timeout case this fix
        // was found alongside).
        subscription.dispose();

        // No usage metadata was ever observed (the stream never emitted), so
        // the release is for the full original reservation - actualTokens=0.
        verify(usageLimiterService).reconcileTokenReservation(TENANT_ID, RESERVED_TOKENS, 0L);
    }

    @Test
    void normalCompletionStillReconcilesExactlyOnce() {
        FreeTierUsageLimiterService usageLimiterService = mock(FreeTierUsageLimiterService.class);
        FreeTierTokenTrackingAdvisor advisor =
            new FreeTierTokenTrackingAdvisor(usageLimiterService, TENANT_ID, RESERVED_TOKENS);

        ChatClientRequest request = mock(ChatClientRequest.class);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        ChatClientResponse response = mock(ChatClientResponse.class);
        when(response.chatResponse()).thenReturn(null);
        when(chain.nextStream(request)).thenReturn(Flux.just(response));

        advisor.adviseStream(request, chain).subscribe();

        // A completed stream must reconcile exactly once - the new
        // doOnCancel hook must not ALSO fire once the sequence has already
        // terminated normally (Reactor guarantees complete/error/cancel are
        // mutually exclusive terminal signals for a given subscription).
        verify(usageLimiterService, org.mockito.Mockito.times(1))
            .reconcileTokenReservation(eq(TENANT_ID), eq(RESERVED_TOKENS), eq(0L));
    }

    @Test
    void errorOnASingleChainLinkDoesNotReconcile() {
        FreeTierUsageLimiterService usageLimiterService = mock(FreeTierUsageLimiterService.class);
        FreeTierTokenTrackingAdvisor advisor =
            new FreeTierTokenTrackingAdvisor(usageLimiterService, TENANT_ID, RESERVED_TOKENS);

        ChatClientRequest request = mock(ChatClientRequest.class);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(request)).thenReturn(Flux.error(new RuntimeException("provider blew up")));

        advisor.adviseStream(request, chain).subscribe(v -> { }, err -> { });

        // Unchanged existing behavior: an error is a retryable chain-level
        // failure, not the turn's outcome, so it must NOT reconcile here -
        // only the outer HostedPoolExhaustedException path (or a later
        // link's own success) does.
        verify(usageLimiterService, never()).reconcileTokenReservation(eq(TENANT_ID), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    // ---- 2026-08-11 double-reconciliation fix -----------------------------

    @Test
    void firstCompletionReconcilesRealUsageAgainstTheReservation() {
        FreeTierUsageLimiterService usageLimiterService = mock(FreeTierUsageLimiterService.class);
        FreeTierTokenTrackingAdvisor advisor =
            new FreeTierTokenTrackingAdvisor(usageLimiterService, TENANT_ID, RESERVED_TOKENS);

        // Built BEFORE the when() below - the helper stubs its own mocks, and
        // Mockito forbids interleaved stubbing inside a pending when().
        ChatClientResponse answer = responseWithTotalTokens(4200);
        ChatClientRequest request = mock(ChatClientRequest.class);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(request)).thenReturn(Flux.just(answer));

        advisor.adviseStream(request, chain).subscribe();

        // The very first terminal reconciliation is unchanged by the fix: it
        // sees the full captured reservation AND the real observed usage.
        verify(usageLimiterService).reconcileTokenReservation(TENANT_ID, RESERVED_TOKENS, 4200L);
    }

    @Test
    void extraSameTurnCallAfterCompletionRecordsAdditivelyAndNeverReReleasesTheReservation() {
        FreeTierUsageLimiterService usageLimiterService = mock(FreeTierUsageLimiterService.class);
        FreeTierTokenTrackingAdvisor advisor =
            new FreeTierTokenTrackingAdvisor(usageLimiterService, TENANT_ID, RESERVED_TOKENS);

        // Turn's user-facing answer streams to completion first...
        // (helper responses built before their when() - see note above)
        ChatClientResponse answer = responseWithTotalTokens(4200);
        ChatClientRequest streamRequest = mock(ChatClientRequest.class);
        StreamAdvisorChain streamChain = mock(StreamAdvisorChain.class);
        when(streamChain.nextStream(streamRequest)).thenReturn(Flux.just(answer));
        advisor.adviseStream(streamRequest, streamChain).subscribe();

        // ...then the SAME ChatClient (same advisor instance) is reused for
        // an extra same-turn call - the sampled quality judge, consistency
        // checker, toxicity guardrail, or remember_fact entity extraction all
        // take this .call() path.
        ChatClientResponse judgeAnswer = responseWithTotalTokens(500);
        ChatClientRequest judgeRequest = mock(ChatClientRequest.class);
        CallAdvisorChain callChain = mock(CallAdvisorChain.class);
        when(callChain.nextCall(judgeRequest)).thenReturn(judgeAnswer);
        advisor.adviseCall(judgeRequest, callChain);

        // First reconciliation consumed the reservation with the answer's
        // real usage; the judge call's REAL 500 tokens still count (it is
        // real hosted-pool spend) but with reservation 0 - the additive
        // branch. Before the fix this second call re-passed RESERVED_TOKENS,
        // crediting the tenant reserved-minus-500 phantom tokens.
        verify(usageLimiterService).reconcileTokenReservation(TENANT_ID, RESERVED_TOKENS, 4200L);
        verify(usageLimiterService).reconcileTokenReservation(TENANT_ID, 0L, 500L);
        // The reservation must have been seen exactly once, ever.
        verify(usageLimiterService, org.mockito.Mockito.times(1))
            .reconcileTokenReservation(eq(TENANT_ID), eq(RESERVED_TOKENS), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void cancelThenExtraCallDoesNotReleaseTheReservationTwice() {
        FreeTierUsageLimiterService usageLimiterService = mock(FreeTierUsageLimiterService.class);
        FreeTierTokenTrackingAdvisor advisor =
            new FreeTierTokenTrackingAdvisor(usageLimiterService, TENANT_ID, RESERVED_TOKENS);

        // Client aborts mid-stream: the cancel hook releases the full
        // reservation (the 2026-08-09 leak fix) and must consume the guard.
        ChatClientRequest streamRequest = mock(ChatClientRequest.class);
        StreamAdvisorChain streamChain = mock(StreamAdvisorChain.class);
        when(streamChain.nextStream(streamRequest)).thenReturn(Flux.never());
        Disposable subscription = advisor.adviseStream(streamRequest, streamChain).subscribe();
        subscription.dispose();

        // A straggler same-turn call still lands after the abort (e.g. an
        // async judge sample already in flight) - its real usage must record
        // additively, never re-releasing the already-released reservation.
        ChatClientResponse lateAnswer = responseWithTotalTokens(500);
        ChatClientRequest lateRequest = mock(ChatClientRequest.class);
        CallAdvisorChain callChain = mock(CallAdvisorChain.class);
        when(callChain.nextCall(lateRequest)).thenReturn(lateAnswer);
        advisor.adviseCall(lateRequest, callChain);

        verify(usageLimiterService).reconcileTokenReservation(TENANT_ID, RESERVED_TOKENS, 0L);
        verify(usageLimiterService).reconcileTokenReservation(TENANT_ID, 0L, 500L);
        verify(usageLimiterService, org.mockito.Mockito.times(1))
            .reconcileTokenReservation(eq(TENANT_ID), eq(RESERVED_TOKENS), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void failedLinkDoesNotConsumeTheGuardSoALaterLinkStillSeesTheReservation() {
        FreeTierUsageLimiterService usageLimiterService = mock(FreeTierUsageLimiterService.class);
        FreeTierTokenTrackingAdvisor advisor =
            new FreeTierTokenTrackingAdvisor(usageLimiterService, TENANT_ID, RESERVED_TOKENS);

        // Link 1 of the fallback chain errors out - reconciles nothing (the
        // long-standing no-doOnError rule) and, critically for this fix, must
        // NOT flip the once-guard: the error never reaches reconcile() at all.
        ChatClientRequest failingRequest = mock(ChatClientRequest.class);
        StreamAdvisorChain failingChain = mock(StreamAdvisorChain.class);
        when(failingChain.nextStream(failingRequest)).thenReturn(Flux.error(new RuntimeException("provider blew up")));
        advisor.adviseStream(failingRequest, failingChain).subscribe(v -> { }, err -> { });

        // Link 2 succeeds - the SAME shared advisor instance's first actual
        // reconciliation, which must still see the untouched reservation.
        ChatClientResponse retryAnswer = responseWithTotalTokens(4200);
        ChatClientRequest retryRequest = mock(ChatClientRequest.class);
        StreamAdvisorChain retryChain = mock(StreamAdvisorChain.class);
        when(retryChain.nextStream(retryRequest)).thenReturn(Flux.just(retryAnswer));
        advisor.adviseStream(retryRequest, retryChain).subscribe();

        verify(usageLimiterService).reconcileTokenReservation(TENANT_ID, RESERVED_TOKENS, 4200L);
    }

    // ---- 2026-10-05 merged-stream-usage fix ---------------------------------

    /** A single stream chunk whose usage reports only the given
     * prompt/completion tokens - half of the split-usage shape (Anthropic
     * reports input tokens on {@code message_start}, output tokens on {@code
     * message_delta}). */
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
        FreeTierUsageLimiterService usageLimiterService = mock(FreeTierUsageLimiterService.class);
        FreeTierTokenTrackingAdvisor advisor =
            new FreeTierTokenTrackingAdvisor(usageLimiterService, TENANT_ID, RESERVED_TOKENS);

        // Anthropic's real stream shape: input on the first chunk, output on
        // the last. Reading only the last chunk (the old bug) reconciled 200
        // completion tokens and dropped the 4000 input - the tenant's token
        // window under-debited versus what llm_call_log recorded (200 vs 4200).
        // Chunks built BEFORE the when() - each helper stubs its own mocks,
        // and Mockito forbids interleaved stubbing inside a pending when().
        ChatClientResponse inputChunk = chunkWithUsage(4000, null);
        ChatClientResponse outputChunk = chunkWithUsage(null, 200);
        ChatClientRequest request = mock(ChatClientRequest.class);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(request)).thenReturn(Flux.just(inputChunk, outputChunk));

        advisor.adviseStream(request, chain).subscribe();

        // The merged Usage is a DefaultUsage(4000, 200) -> total 4200.
        verify(usageLimiterService).reconcileTokenReservation(TENANT_ID, RESERVED_TOKENS, 4200L);
    }
}
