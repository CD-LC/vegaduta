package com.agenticai.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

class TenantScopeTest {

    private static TenantContext ctx(String tenant) {
        return TenantContext.of(tenant, "u", Set.of());
    }

    private static String current() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse(null);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        AgentVersionPin.clear();
    }

    @Test
    void capturedPinIsAppliedOnTheWorkerThreadAndNotLeaked() throws Exception {
        UUID pin = UUID.randomUUID();
        TenantContext.set(ctx("a"));
        AgentVersionPin.set(pin);
        TenantScope scope = TenantScope.capture();
        AtomicReference<UUID> before = new AtomicReference<>();
        AtomicReference<UUID> inside = new AtomicReference<>();
        AtomicReference<UUID> after = new AtomicReference<>(UUID.randomUUID());
        Thread t =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    before.set(AgentVersionPin.current());
                                    scope.run(() -> inside.set(AgentVersionPin.current()));
                                    after.set(AgentVersionPin.current());
                                });
        t.join();
        assertNull(before.get());
        assertEquals(pin, inside.get());
        assertNull(after.get());
    }

    @Test
    void aStalePinOnAPooledThreadIsHiddenFromTenantWorkAndRestoredAfter() {
        TenantContext.set(ctx("a"));
        TenantScope scope = TenantScope.capture(); // no pin on the capturing thread
        TenantContext.clear();
        UUID stale = UUID.randomUUID();
        AgentVersionPin.set(stale);

        AtomicReference<UUID> inside = new AtomicReference<>(stale);
        scope.run(() -> inside.set(AgentVersionPin.current()));

        assertNull(inside.get());
        assertEquals(stale, AgentVersionPin.current());
    }

    @Test
    void aPinSetInsideTheWorkDoesNotLeakOutOfEitherKindOfScope() {
        UUID leaked = UUID.randomUUID();
        TenantScope.of(ctx("a")).run(() -> AgentVersionPin.set(leaked));
        assertNull(AgentVersionPin.current());

        TenantContext.set(ctx("a"));
        TenantScope captured = TenantScope.capture();
        TenantContext.clear();
        assertThrows(
                IllegalStateException.class,
                () ->
                        captured.run(
                                () -> {
                                    AgentVersionPin.set(leaked);
                                    throw new IllegalStateException("boom");
                                }));
        assertNull(AgentVersionPin.current());
    }

    @Test
    void ofKeepsTheExecutingThreadsPinForTheWork() {
        UUID pin = UUID.randomUUID();
        AgentVersionPin.set(pin);
        AtomicReference<UUID> inside = new AtomicReference<>();
        TenantScope.of(ctx("a")).run(() -> inside.set(AgentVersionPin.current()));
        assertEquals(pin, inside.get());
        assertEquals(pin, AgentVersionPin.current());
    }

    @Test
    void appliesCapturedTenantAndClearsAfterwards() {
        TenantContext.set(ctx("a"));
        TenantScope scope = TenantScope.capture();
        TenantContext.clear();

        assertEquals("a", scope.get(TenantScopeTest::current));
        assertNull(current());
    }

    private static void assertNull(Object o) {
        assertEquals(null, o);
    }

    @Test
    void restoresPreviousValueAfterWork() {
        TenantScope scope = TenantScope.of(ctx("a"));
        TenantContext.set(ctx("outer"));
        assertEquals("a", scope.get(TenantScopeTest::current));
        assertEquals("outer", current());
    }

    @Test
    void restoresAfterException() {
        TenantScope scope = TenantScope.of(ctx("a"));
        TenantContext.set(ctx("outer"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        scope.run(
                                () -> {
                                    throw new IllegalStateException("boom");
                                }));
        assertEquals("outer", current());

        TenantContext.clear();
        assertThrows(
                IllegalStateException.class,
                () ->
                        scope.run(
                                () -> {
                                    throw new IllegalStateException("boom");
                                }));
        assertNull(current());
    }

    @Test
    void nestedScopesUnwindInOrder() {
        TenantScope a = TenantScope.of(ctx("a"));
        TenantScope b = TenantScope.of(ctx("b"));
        StringBuilder seen = new StringBuilder();
        a.run(
                () -> {
                    seen.append(current());
                    b.run(() -> seen.append(current()));
                    seen.append(current());
                });
        assertEquals("aba", seen.toString());
        assertNull(current());
    }

    @Test
    void emptyScopeLeavesOuterScopeUntouched() {
        TenantScope none = TenantScope.of(Optional.empty());
        assertTrue(none.isEmpty());
        TenantContext.set(ctx("outer"));
        assertEquals("outer", none.get(TenantScopeTest::current));
        assertEquals("outer", current());
        none.run(() -> assertEquals("outer", current()));
        assertEquals("outer", current());
    }

    @Test
    void captureWithNoContextIsEmpty() {
        assertTrue(TenantScope.capture().isEmpty());
        assertFalse(TenantScope.of(ctx("a")).isEmpty());
        assertEquals(
                "a", TenantScope.of(Optional.of(ctx("a"))).context().orElseThrow().getTenantId());
    }

    @Test
    void wrappersApplyTenantOnExecution() throws Exception {
        TenantScope scope = TenantScope.of(ctx("a"));
        AtomicReference<String> ran = new AtomicReference<>();
        Runnable r = scope.wrapRunnable(() -> ran.set(current()));
        Supplier<String> s = scope.wrapSupplier(TenantScopeTest::current);
        Callable<String> c = scope.wrapCallable(TenantScopeTest::current);
        Function<String, String> f = scope.wrapFunction(x -> x + current());
        r.run();
        assertEquals("a", ran.get());
        assertEquals("a", s.get());
        assertEquals("a", c.call());
        assertEquals("xa", f.apply("x"));
        assertNull(current());
    }

    @Test
    void worksOnVirtualThreadAndDoesNotLeak() throws Exception {
        TenantContext.set(ctx("a"));
        TenantScope scope = TenantScope.capture();
        AtomicReference<String> inside = new AtomicReference<>();
        AtomicReference<String> after = new AtomicReference<>("unset");
        Thread t =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    assertNull(current());
                                    scope.run(() -> inside.set(current()));
                                    after.set(current());
                                });
        t.join();
        assertEquals("a", inside.get());
        assertNull(after.get());
        assertEquals("a", current());
    }
}
