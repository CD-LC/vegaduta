package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link FimCompletionService} on its real worker pool: a call that outruns its deadline is settled when it
 * lands (or abandoned if it then fails), a saturated pool refunds in full, the worker thread carries the
 * request thread's {@link TenantContext}, and the pool row is cached per TTL. Contract overview in
 * {@link FimCompletionServiceTest}.
 */
class FimCompletionWorkerTest extends FimCompletionServiceFixture {

    // ---- the deadline, on the real pool ------------------------------------

    /**
     * The cost leak this exists to prevent: the editor is answered empty at
     * the deadline, but the request is still with the provider and the tokens
     * it produces are billed. So the reservation is held, not refunded, and
     * settled with the REAL usage when the call finally lands.
     */
    @Test
    void aCallThatOutrunsItsDeadlineIsSettledWhenItLandsRatherThanRefunded() throws Exception {
        WorkerService service = worker(entitlements, 40L, 2, worker -> {
            worker.awaitRelease();
            return responseWith("  return a + b;", 412, 9);
        });

        FimCompletionService.FimCompletionResult result = service.complete("int f() {\n", "\n}", "java", null);

        assertEquals("", result.completion(), "the editor is not made to wait past the deadline");
        assertEquals(0, entitlements.settled, "the call has not landed yet");
        assertEquals(0, entitlements.abandoned, "and it must NOT be refunded - the provider is still billing it");

        service.release();
        assertTrue(entitlements.terminal.await(5, TimeUnit.SECONDS), "the lease must be terminated once the call lands");
        assertEquals(1, entitlements.settled);
        assertEquals(0, entitlements.abandoned);
        assertEquals("test-model", entitlements.settledModel);
        assertEquals(Long.valueOf(412L), entitlements.settledPromptTokens);
        assertEquals(Long.valueOf(9L), entitlements.settledCompletionTokens);
    }

    /** Same path, but the slow call ends in a provider failure - nothing was produced, so it is refunded. */
    @Test
    void aCallThatOutrunsItsDeadlineAndThenFailsIsAbandoned() throws Exception {
        WorkerService service = worker(entitlements, 40L, 2, worker -> {
            worker.awaitRelease();
            throw new IllegalStateException("provider 500 after the deadline");
        });

        assertEquals("", service.complete("a", "b", "java", null).completion());

        service.release();
        assertTrue(entitlements.terminal.await(5, TimeUnit.SECONDS));
        assertEquals(1, entitlements.abandoned);
        assertEquals(0, entitlements.settled);
    }

    /** A call that beats the deadline settles inline, on the request thread, exactly as before. */
    @Test
    void aCallThatBeatsItsDeadlineSettlesInline() {
        WorkerService service = worker(entitlements, 5_000L, 2, worker -> responseWith("  return a + b;", 100, 7));

        FimCompletionService.FimCompletionResult result = service.complete("int f() {\n", "\n}", "java", null);

        assertEquals("  return a + b;", result.completion());
        assertEquals("test-model", result.model());
        assertEquals(1, entitlements.settled);
        assertEquals(0, entitlements.abandoned);
        assertEquals(Long.valueOf(100L), entitlements.settledPromptTokens);
    }

    /**
     * Direct handoff, no queue: with every worker occupied the next request is
     * rejected before any provider is touched, so its reservation is released
     * in full.
     */
    @Test
    void aSaturatedPoolRefundsTheRejectedRequestInFull() throws Exception {
        WorkerService service = worker(entitlements, 10_000L, 1, worker -> {
            worker.awaitRelease();
            return responseWith("late", 1, 1);
        });

        Thread occupier = new Thread(() -> service.complete("a", "b", "java", null), "occupier");
        occupier.setDaemon(true);
        occupier.start();
        assertTrue(service.started.await(5, TimeUnit.SECONDS), "the single worker must actually be running");

        FimCompletionService.FimCompletionResult rejected = service.complete("a", "b", "java", null);

        assertEquals("", rejected.completion());
        assertEquals(2, entitlements.reservedMaxTokens.size(), "both requests reserved before the pool was consulted");
        assertEquals(1, entitlements.abandoned, "only the rejected one is terminal so far - the other is still running");
        assertEquals(0, entitlements.settled);
    }

    // ---- tenant context across the thread hop ------------------------------

    /**
     * This platform is schema-per-tenant: a model call that runs with no
     * {@link TenantContext} does not merely mis-tag a metric, it lets
     * everything the advisor chain writes per call resolve against the
     * {@code public} schema. So the call must really run on a pool thread AND
     * really carry the request thread's context.
     */
    @Test
    void theWorkerThreadCarriesTheRequestThreadsTenantContext() {
        WorkerService service = worker(entitlements, 5_000L, 2, worker -> responseWith("x", 1, 1));
        TenantContext.set(TenantContext.of("acme_tenant", "user-42", Set.of("ROLE_user")));

        service.complete("a", "b", "java", null);

        assertNotNull(service.workerThreadName, "the call must have run somewhere");
        assertTrue(
            service.workerThreadName.startsWith("fim-completion-"),
            "the call should run on this service's own pool, not the request thread - saw " + service.workerThreadName);
        assertNotNull(service.workerTenantId, "the worker thread saw no tenant context at all");
        assertEquals("acme_tenant", service.workerTenantId);
        assertEquals("acme_tenant", TenantContext.current().orElseThrow().getTenantId(),
            "and the request thread's own context must be left exactly as it was");
    }

    /** No tenant context (the "security" profile off) is a no-op, not a crash. */
    @Test
    void aRequestWithNoTenantContextStillCompletes() {
        WorkerService service = worker(entitlements, 5_000L, 2, worker -> responseWith("  x", 1, 1));

        assertEquals("  x", service.complete("a", "b", "java", null).completion());
        assertNull(service.workerTenantId);
        assertTrue(TenantContext.current().isEmpty());
    }

    // ---- the pool-row cache ------------------------------------------------

    @Test
    void theResolvedPoolRowIsCachedRatherThanReReadPerKeystroke() {
        WorkerService service = worker(entitlements, 5_000L, 2, 60_000L, worker -> responseWith("x", 1, 1));

        service.complete("a", "b", "java", null);
        service.complete("c", "d", "java", null);
        service.complete("e", "f", "java", null);

        verify(hostedProviders, times(1)).resolveEnabledChain();
    }

    @Test
    void aZeroTtlResolvesThePoolOnEveryRequest() {
        WorkerService service = worker(entitlements, 5_000L, 2, 0L, worker -> responseWith("x", 1, 1));

        service.complete("a", "b", "java", null);
        service.complete("c", "d", "java", null);

        verify(hostedProviders, times(2)).resolveEnabledChain();
    }
}
