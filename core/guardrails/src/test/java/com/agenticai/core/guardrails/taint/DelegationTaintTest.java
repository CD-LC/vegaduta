package com.agenticai.core.guardrails.taint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class DelegationTaintTest {

    private static TurnTaint tainted(String... sources) {
        TurnTaint t = new TurnTaint();
        for (String s : sources) {
            t.mark(s);
        }
        return t;
    }

    @Test
    void aChildOfATaintedParentIsTaintedWithTheParentsSources() {
        TurnTaint child = new TurnTaint();
        child.inheritFrom(tainted("fetch_web_page", "inbound:email"));

        assertTrue(child.isTainted());
        assertEquals(List.of("fetch_web_page", "inbound:email"), child.sources());
    }

    @Test
    void aChildOfACleanOrMissingParentIsClean() {
        TurnTaint child = new TurnTaint();
        child.inheritFrom(new TurnTaint());
        child.inheritFrom(null);
        assertFalse(child.isTainted());
    }

    @Test
    void inheritingIsACopyNotALink() {
        TurnTaint parent = new TurnTaint();
        TurnTaint child = new TurnTaint();
        child.inheritFrom(parent);

        parent.mark("later");
        assertFalse(child.isTainted(), "a mark the parent gets after the child started does not reach it");

        TurnTaint other = new TurnTaint();
        other.inheritFrom(parent);
        other.mark("only-the-child");
        assertFalse(parent.sources().contains("only-the-child"));
    }

    @Test
    void childOfCurrentCopiesWhatTheCallingTurnHadAtThatMoment() {
        TurnTaint parent = new TurnTaint();
        try (DelegationTaint.Scope ignored = DelegationTaint.openCall(parent)) {
            TurnTaint early = DelegationTaint.childOfCurrent();
            parent.mark("fetch_web_page");
            TurnTaint late = DelegationTaint.childOfCurrent();

            assertFalse(early.isTainted());
            assertTrue(late.isTainted());
            assertNotSame(parent, late);
        }
    }

    @Test
    void outsideAScopeTheChildIsCleanAndScopesNestAndRestore() {
        assertFalse(DelegationTaint.childOfCurrent().isTainted());
        TurnTaint outer = tainted("a");
        try (DelegationTaint.Scope o = DelegationTaint.openCall(outer)) {
            try (DelegationTaint.Scope i = DelegationTaint.openCall(new TurnTaint())) {
                assertFalse(DelegationTaint.childOfCurrent().isTainted(), "the inner call's own (clean) turn");
            }
            assertTrue(DelegationTaint.childOfCurrent().isTainted(), "the outer scope is restored");
        }
        assertTrue(DelegationTaint.currentCall().isEmpty(), "nothing leaks onto the thread afterwards");
    }

    @Test
    void aFanOutBranchSeesTheTaintCapturedOnTheOriginatingThread() throws Exception {
        TurnTaint parent = tainted("fetch_web_page");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (DelegationTaint.Scope ignored = DelegationTaint.openCall(parent)) {
            Callable<Boolean> plain = () -> DelegationTaint.childOfCurrent().isTainted();
            Callable<Boolean> carried = DelegationTaint.propagating(plain);

            assertTrue(pool.submit(carried).get(), "the carried scope reaches the worker thread");
            assertFalse(pool.submit(plain).get(), "a thread that was not handed the scope sees nothing");
            assertTrue(pool.submit(carried).get());
            assertTrue(pool.submit(() -> DelegationTaint.currentCall().isEmpty()).get(), "the worker is left clean");
        } finally {
            pool.shutdownNow();
        }
    }
}
