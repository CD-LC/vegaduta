package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * What this suite locks in is the ENTITLEMENT contract of {@link
 * FimCompletionService} - not completion quality (prompt shape and cleanup
 * have their own suites) - because this endpoint spends platform-paid hosted
 * inference on every keystroke. The invariants exercised here:
 *
 * <ul>
 *   <li>the quota/budget gate runs BEFORE any provider is touched, and a
 *       rejection propagates unchanged out of the service AND out of {@link
 *       FimCompletionController} (see the note below on what "402" coverage
 *       can and cannot mean inside this module);</li>
 *   <li>every reservation reaches exactly one terminal call - {@code settle}
 *       when a model answered, {@code abandon} when no call was made or the
 *       call produced nothing, and for a call that outran its deadline, a
 *       {@code settle} deferred to whenever that call actually lands;</li>
 *   <li>a call that DID happen is settled even when the cleaned completion
 *       turns out empty - the tokens were spent either way;</li>
 *   <li>the caller's {@code maxTokens} is honoured only downwards, and it is
 *       the CLAMPED value that gets reserved;</li>
 *   <li>with no entitlements adapter registered the service refuses rather
 *       than serving unmetered, and only THAT refusal becomes a 503;</li>
 *   <li>the model call really does run on a pool thread carrying the request
 *       thread's {@link TenantContext}, and the pool row is resolved from the
 *       database once per TTL rather than once per request.</li>
 * </ul>
 *
 * <p><b>What is NOT covered here, precisely.</b> The HTTP status a quota
 * rejection ends up with. {@code PerUserBudgetExhaustedException} (the 402
 * carrier) lives in {@code core/agents} and {@code GlobalExceptionHandler}
 * (which maps it) lives in {@code core/app}; {@code core/chat} depends on
 * neither, so nothing in this module can assert "402". What these tests pin
 * is the half that is this module's to get wrong: the exception instance
 * comes out of the service and then out of the controller unchanged and
 * un-wrapped, which is the precondition for that handler's type-based
 * mapping. A test that actually asserts the status has to live in {@code
 * core/app}.
 *
 * <p>Mockito for the pool resolver and client factory (both concrete Spring
 * components with JPA/crypto collaborators - same reasoning as {@code
 * ErrorExplanationServiceTest}). Two seams are used instead of mocking
 * {@code ChatClient}'s fluent chain: {@link
 * FimCompletionService#invokeModel} for tests about what {@code complete}
 * does with an outcome (this class), and {@link FimCompletionService#callOnWorker} - which
 * runs on the real pool, under the real deadline - for tests about the thread
 * hop, the deadline and the pool cache ({@link FimCompletionWorkerTest}). Both share
 * {@link FimCompletionServiceFixture}.
 */
class FimCompletionServiceTest extends FimCompletionServiceFixture {

    // ---- the gate ----------------------------------------------------------

    /**
     * The per-user dollar budget answers 402 by throwing {@code
     * PerUserBudgetExhaustedException}, a subclass of {@link
     * TenantLimitExceededException} that lives in {@code core/agents} and so
     * cannot be referenced from this module (that is the whole reason {@link
     * FimCompletionEntitlements} is a port). A local subclass stands in for
     * it here; what is being asserted is that the service does not swallow,
     * wrap or translate it - {@code GlobalExceptionHandler} maps it to 402 by
     * type, and only an unchanged exception gets that mapping.
     */
    private static final class StandInBudgetExhausted extends TenantLimitExceededException {
        StandInBudgetExhausted() {
            super("per-user budget exhausted");
        }
    }

    @Test
    void aBudgetRejectionPropagatesUnchangedAndNoModelIsEverCalled() {
        entitlements.rejectWith = new StandInBudgetExhausted();
        CountingService service = service(entitlements, new FimCompletionService.ModelReply("x", "m", 1L, 1L));

        StandInBudgetExhausted thrown = assertThrows(
            StandInBudgetExhausted.class,
            () -> service.complete("int a = ", ";", "java", null)
        );

        assertSame(entitlements.rejectWith, thrown, "the 402-bearing exception must reach the handler untouched");
        assertEquals(0, service.modelInvocations, "the gate must run before any provider is touched");
        assertEquals(0, entitlements.settled, "a rejected reservation is never settled");
        assertEquals(0, entitlements.abandoned, "reserve() threw, so there is no lease to abandon");
    }

    @Test
    void aPlainQuotaRejectionPropagatesToo() {
        entitlements.rejectWith = new TenantLimitExceededException("daily message quota reached");
        CountingService service = service(entitlements, new FimCompletionService.ModelReply("x", "m", 1L, 1L));

        assertThrows(TenantLimitExceededException.class, () -> service.complete("a", "b", "java", null));
        assertEquals(0, service.modelInvocations);
    }

    @Test
    void withNoEntitlementsAdapterEveryRequestIsRefusedRatherThanServedUnmetered() {
        CountingService service = service(null, new FimCompletionService.ModelReply("x", "m", 1L, 1L));

        assertFalse(service.metered());
        assertThrows(
            FimCompletionService.EntitlementsUnavailableException.class,
            () -> service.complete("a", "b", "java", null));
        assertEquals(0, service.modelInvocations);
    }

    /**
     * The adapter is resolved per call until one is found, so a deployment
     * that registers it after this bean was constructed heals on the next
     * request instead of answering 503 until someone restarts the process.
     */
    @Test
    void anAdapterThatAppearsAfterConstructionIsPickedUpWithoutARestart() {
        @SuppressWarnings("unchecked")
        ObjectProvider<FimCompletionEntitlements> provider = mock(ObjectProvider.class);
        // Three answers, because the constructor's own probe consumes the
        // first: absent at construction, still absent on the first request,
        // registered by the second.
        when(provider.getIfAvailable()).thenReturn(null, null, entitlements);
        CountingService service = new CountingService(
            provider, hostedProviders, chatClientFactory,
            FimCompletionService.ModelCall.of(new FimCompletionService.ModelReply("  x", "m", 1L, 1L)));

        assertThrows(
            FimCompletionService.EntitlementsUnavailableException.class,
            () -> service.complete("a", "b", "java", null),
            "absent at construction time means refuse, not serve unmetered");
        assertEquals("  x", service.complete("a", "b", "java", null).completion());
        assertEquals(1, entitlements.settled);
    }

    // ---- the controller boundary ------------------------------------------

    /**
     * The controller's only {@code catch} is the missing-adapter one. A quota
     * rejection must pass straight through it, as the same instance - if it
     * were caught and re-thrown as a {@code ResponseStatusException} the
     * handler's type-based 402/403 mapping would never see it.
     */
    @Test
    void theControllerHandsABudgetRejectionToTheExceptionHandlerUntouched() {
        entitlements.rejectWith = new StandInBudgetExhausted();
        FimCompletionController controller = new FimCompletionController(
            service(entitlements, new FimCompletionService.ModelReply("x", "m", 1L, 1L)));

        RuntimeException thrown = assertThrows(
            RuntimeException.class,
            () -> controller.fim(new FimCompletionController.FimCompletionRequest("int a = ", ";", "java", null)));

        assertSame(entitlements.rejectWith, thrown);
        assertInstanceOf(TenantLimitExceededException.class, thrown);
    }

    @Test
    void theControllerHandsAPlainQuotaRejectionThroughToo() {
        entitlements.rejectWith = new TenantLimitExceededException("daily message quota reached");
        FimCompletionController controller = new FimCompletionController(
            service(entitlements, new FimCompletionService.ModelReply("x", "m", 1L, 1L)));

        RuntimeException thrown = assertThrows(
            RuntimeException.class,
            () -> controller.fim(new FimCompletionController.FimCompletionRequest("a", "b", "java", null)));

        assertSame(entitlements.rejectWith, thrown);
    }

    @Test
    void onlyTheMissingAdapterBecomesA503() {
        FimCompletionController controller = new FimCompletionController(
            service(null, new FimCompletionService.ModelReply("x", "m", 1L, 1L)));

        ResponseStatusException thrown = assertThrows(
            ResponseStatusException.class,
            () -> controller.fim(new FimCompletionController.FimCompletionRequest("a", "b", "java", null)));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.valueOf(thrown.getStatusCode().value()));
    }

    /**
     * An {@link IllegalStateException} raised INSIDE the metering adapter is a
     * server fault, not an incomplete deployment: it must not be laundered
     * into the 503 that says "no entitlements adapter is registered".
     */
    @Test
    void anIllegalStateFromInsideTheAdapterIsNotReportedAsAMissingAdapter() {
        entitlements.rejectWith = new IllegalStateException("redis connection pool exhausted");
        FimCompletionController controller = new FimCompletionController(
            service(entitlements, new FimCompletionService.ModelReply("x", "m", 1L, 1L)));

        RuntimeException thrown = assertThrows(
            RuntimeException.class,
            () -> controller.fim(new FimCompletionController.FimCompletionRequest("a", "b", "java", null)));

        assertSame(entitlements.rejectWith, thrown);
        assertFalse(thrown instanceof ResponseStatusException, "a metering fault must not be answered as 503");
    }

    // ---- settlement --------------------------------------------------------

    @Test
    void aServedCompletionSettlesTheLeaseWithTheReportedUsage() {
        CountingService service = service(
            entitlements, new FimCompletionService.ModelReply("  return a + b;", "llama-3.1-8b-instant", 412L, 9L));

        FimCompletionService.FimCompletionResult result = service.complete("int f() {\n", "\n}", "java", null);

        assertEquals("  return a + b;", result.completion());
        assertEquals("llama-3.1-8b-instant", result.model());
        assertEquals(1, entitlements.settled);
        assertEquals(0, entitlements.abandoned);
        assertEquals("llama-3.1-8b-instant", entitlements.settledModel);
        assertEquals(Long.valueOf(412L), entitlements.settledPromptTokens);
        assertEquals(Long.valueOf(9L), entitlements.settledCompletionTokens);
    }

    /**
     * The call reached a provider and burned tokens; the reply then cleaned
     * down to nothing (here: pure echo of the prefix). Under-counting is the
     * one failure mode a spend meter must not have, so this settles - it does
     * not abandon.
     */
    @Test
    void aCallThatCleansDownToNothingIsStillMetered() {
        CountingService service = service(
            entitlements, new FimCompletionService.ModelReply("int f() {\n", "some-model", 300L, 4L));

        FimCompletionService.FimCompletionResult result = service.complete("int f() {\n", "\n}", "java", null);

        assertEquals("", result.completion());
        assertNull(result.model(), "an empty completion reports no model");
        assertEquals(1, entitlements.settled, "tokens were spent, so the lease is settled, not abandoned");
        assertEquals(0, entitlements.abandoned);
    }

    @Test
    void anEmptyPoolAbandonsTheReservationAndAnswersEmpty() {
        CountingService service = service(entitlements, (FimCompletionService.ModelReply) null);

        FimCompletionService.FimCompletionResult result = service.complete("a", "b", "java", null);

        assertEquals("", result.completion());
        assertEquals(0, entitlements.settled);
        assertEquals(1, entitlements.abandoned, "nothing was spent, so the reservation is released in full");
    }

    @Test
    void anUpstreamFailureAbandonsTheReservationAndAnswersEmptyRatherThanErroring() {
        CountingService service = service(entitlements, (FimCompletionService.ModelReply) null);
        service.failWith = new IllegalStateException("provider 503");

        FimCompletionService.FimCompletionResult result = service.complete("a", "b", "java", null);

        assertEquals("", result.completion(), "an editor has nothing to do with a provider outage");
        assertEquals(1, entitlements.abandoned);
        assertEquals(0, entitlements.settled);
    }

    @Test
    void anEmptyCursorContextIsAnsweredWithoutReservingAnything() {
        CountingService service = service(entitlements, new FimCompletionService.ModelReply("x", "m", 1L, 1L));

        assertEquals("", service.complete(null, null, "java", null).completion());
        assertTrue(entitlements.reservedMaxTokens.isEmpty(), "no quota is consumed for a request with no context");
        assertEquals(0, service.modelInvocations);
    }

    /** A deferred outcome means the in-flight call owns the lease - {@code complete} must not touch it. */
    @Test
    void aDeferredOutcomeLeavesTheLeaseAloneInsteadOfRefundingIt() {
        CountingService service = service(entitlements, FimCompletionService.ModelCall.deferred());

        assertEquals("", service.complete("a", "b", "java", null).completion());
        assertEquals(0, entitlements.abandoned, "a call that is still running must not be refunded");
        assertEquals(0, entitlements.settled, "nor settled yet - the usage is not known until it lands");
        assertEquals(List.of(DEFAULT_MAX_TOKENS), entitlements.reservedMaxTokens);
    }

    // ---- the token ceiling -------------------------------------------------

    @Test
    void theCallerMayLowerMaxTokensButNotRaiseIt() {
        CountingService service = service(entitlements, new FimCompletionService.ModelReply("x", "m", 1L, 1L));

        assertEquals(16, service.clampMaxTokens(16));
        assertEquals(DEFAULT_MAX_TOKENS, service.clampMaxTokens(null));
        assertEquals(DEFAULT_MAX_TOKENS, service.clampMaxTokens(0));
        assertEquals(DEFAULT_MAX_TOKENS, service.clampMaxTokens(-5));
        assertEquals(CEILING_MAX_TOKENS, service.clampMaxTokens(100_000));
    }

    @Test
    void itIsTheClampedValueThatGetsReserved() {
        CountingService service = service(entitlements, new FimCompletionService.ModelReply("x", "m", 1L, 1L));

        service.complete("a", "b", "java", 100_000);

        assertEquals(List.of(CEILING_MAX_TOKENS), entitlements.reservedMaxTokens,
            "a caller must not be able to reserve more budget than the server's ceiling");
    }
}
