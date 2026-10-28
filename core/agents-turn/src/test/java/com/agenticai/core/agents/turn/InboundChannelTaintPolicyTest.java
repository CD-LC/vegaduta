package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.guardrails.taint.TurnTaint;
import org.junit.jupiter.api.Test;

class InboundChannelTaintPolicyTest {

    @Test
    void defaultIsOffAndMarksNothing() {
        InboundChannelTaintPolicy policy = new InboundChannelTaintPolicy("off");
        TurnTaint taint = new TurnTaint();

        assertFalse(policy.apply(taint, false));
        assertFalse(taint.isTainted());
        assertEquals(InboundChannelTaintPolicy.Scope.OFF, new InboundChannelTaintPolicy("").scope());
        assertEquals(InboundChannelTaintPolicy.Scope.OFF, new InboundChannelTaintPolicy("garbage").scope());
    }

    @Test
    void allMarksEverySender() {
        InboundChannelTaintPolicy policy = new InboundChannelTaintPolicy("all");
        TurnTaint owner = new TurnTaint();
        TurnTaint other = new TurnTaint();

        assertTrue(policy.apply(owner, true));
        assertTrue(policy.apply(other, false));
        assertEquals("inbound_non_owner", other.sources().get(0));
    }

    @Test
    void nonOwnerSkipsTheOwner() {
        InboundChannelTaintPolicy policy = new InboundChannelTaintPolicy("non-owner");
        TurnTaint owner = new TurnTaint();
        TurnTaint other = new TurnTaint();

        assertFalse(policy.apply(owner, true));
        assertTrue(policy.apply(other, false));
        assertFalse(owner.isTainted());
        assertTrue(other.isTainted());
    }

    @Test
    void nullTaintIsSafe() {
        assertFalse(new InboundChannelTaintPolicy("all").apply(null, false));
    }
}
