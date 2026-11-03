package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memory.TurnTaintView;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.guardrails.taint.TurnTaint;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Nothing published a TurnTaintView bean, so every memory and knowledge write on a real turn was quarantined. The
 * resolver now reads the turn's own record through the taint policy.
 */
class TurnTaintViewsTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private static AgentTaintPolicy policy(String mode) {
        return new AgentTaintPolicy(
                mode, "", provider(null), provider((MeterRegistry) null), provider((AuditLogService) null));
    }

    @Test
    void aCleanBoundTurnIsNotTainted() {
        AgentTaintPolicy policy = policy("enforce");
        AtomicInteger turn = new AtomicInteger();
        policy.bind(turn, new TurnTaint());

        TurnTaintView view = TurnTaintViews.forTurn(provider(policy), turn, provider(null)).getIfAvailable();

        assertFalse(view.isTainted());
    }

    @Test
    void aMarkMadeAfterTheToolWasBuiltCounts() {
        AgentTaintPolicy policy = policy("enforce");
        AtomicInteger turn = new AtomicInteger();
        TurnTaint record = new TurnTaint();
        policy.bind(turn, record);
        TurnTaintView view = TurnTaintViews.forTurn(provider(policy), turn, provider(null)).getIfAvailable();
        assertFalse(view.isTainted());

        record.mark("fetch_web_page");

        assertTrue(view.isTainted());
        assertTrue(view.sources().contains("fetch_web_page"));
    }

    @Test
    void anotherTurnsTaintIsNotThisTurns() {
        AgentTaintPolicy policy = policy("enforce");
        AtomicInteger tainted = new AtomicInteger();
        AtomicInteger clean = new AtomicInteger();
        TurnTaint taintedRecord = new TurnTaint();
        taintedRecord.mark("inbound:share");
        policy.bind(tainted, taintedRecord);
        policy.bind(clean, new TurnTaint());

        assertTrue(TurnTaintViews.forTurn(provider(policy), tainted, provider(null)).getIfAvailable().isTainted());
        assertFalse(TurnTaintViews.forTurn(provider(policy), clean, provider(null)).getIfAvailable().isTainted());
    }

    @Test
    void observeModeStillTracksSoItQuarantinesToo() {
        AgentTaintPolicy policy = policy("observe");
        AtomicInteger turn = new AtomicInteger();
        TurnTaint record = new TurnTaint();
        record.mark("inbound:whatsapp");
        policy.bind(turn, record);

        assertTrue(TurnTaintViews.forTurn(provider(policy), turn, provider(null)).getIfAvailable().isTainted());
    }

    @Test
    void theLayerSwitchedOffByTheOperatorMeansNothingToQuarantineOn() {
        AgentTaintPolicy policy = policy("off");

        TurnTaintView view = TurnTaintViews.forTurn(provider(policy), new AtomicInteger(), provider(null)).getIfAvailable();

        assertFalse(view.isTainted());
    }

    @Test
    void withoutAPolicyTheFallbackIsUsed() {
        ObjectProvider<TurnTaintView> fallback = provider(mock(TurnTaintView.class));

        assertSame(fallback, TurnTaintViews.forTurn(provider(null), new AtomicInteger(), fallback));
        assertSame(fallback, TurnTaintViews.forTurn(null, new AtomicInteger(), fallback));
    }
}
