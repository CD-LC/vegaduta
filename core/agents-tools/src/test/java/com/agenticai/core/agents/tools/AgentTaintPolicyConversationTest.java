package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.classification.BuiltInToolClassifications;
import com.agenticai.core.agents.tools.classification.ToolClassificationDecision;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.guardrails.taint.ConversationTaint;
import com.agenticai.core.guardrails.taint.ConversationTaintStore;
import com.agenticai.core.guardrails.taint.SenderTrust;
import com.agenticai.core.guardrails.taint.TurnTaint;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Sender-trust seeding and the persisted conversation flag, observed through the gate a sink tool
 * meets in enforce mode (the same path {@code bind} gives the turn's {@code TurnContext} record).
 */
class AgentTaintPolicyConversationTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static AgentTaintPolicy enforcePolicy() {
        ToolClassificationRegistry registry = mock(ToolClassificationRegistry.class);
        when(registry.resolve(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(
                        inv -> {
                            String name = inv.getArgument(0);
                            var declared = BuiltInToolClassifications.all().get(name);
                            return declared != null
                                    ? new ToolClassificationDecision.Classified(declared)
                                    : new ToolClassificationDecision.Blocked(
                                            name,
                                            ToolClassificationDecision.Reason.UNCLASSIFIED,
                                            "unclassified");
                        });
        return new AgentTaintPolicy(
                "enforce",
                "",
                provider(registry),
                provider((MeterRegistry) null),
                provider((AuditLogService) null));
    }

    private static final class MemoryStore implements ConversationTaintStore {
        final Set<String> flags = new HashSet<>();

        @Override
        public synchronized boolean isTainted(String tenantId, String conversationId) {
            return flags.contains(tenantId + "|" + conversationId);
        }

        @Override
        public synchronized void markTainted(String tenantId, String conversationId) {
            flags.add(tenantId + "|" + conversationId);
        }
    }

    private static boolean sinkNeedsApproval(AgentTaintPolicy policy, AtomicInteger turnKey) {
        return policy.gateFor(turnKey, UUID.randomUUID(), "s")
                .check("google_gmail", "{}")
                .requiresApproval();
    }

    @Test
    void anUntrustedSenderGatesASinkAndTheOwnerDoesNot() {
        AgentTaintPolicy policy = enforcePolicy();

        AtomicInteger customer = new AtomicInteger();
        policy.seed(customer, SenderTrust.UNTRUSTED, "inbound:whatsapp");
        assertTrue(sinkNeedsApproval(policy, customer));

        AtomicInteger unknown = new AtomicInteger();
        policy.seed(unknown, null, "inbound:unknown");
        assertTrue(sinkNeedsApproval(policy, unknown), "unknown sender fails closed");

        AtomicInteger owner = new AtomicInteger();
        policy.seed(owner, SenderTrust.TRUSTED, "inbound:owner");
        assertFalse(sinkNeedsApproval(policy, owner));
    }

    @Test
    void aTaintedTurnGatesTheNextTurnOfTheSameConversationOnly() {
        AgentTaintPolicy policy = enforcePolicy();
        ConversationTaint conversations = new ConversationTaint(new MemoryStore());

        // Turn 1: a tool result taints the turn; nothing is gated yet at the start.
        AtomicInteger turn1 = new AtomicInteger();
        TurnTaint record1 = new TurnTaint();
        conversations.attach(record1, "t1", "conv");
        policy.bind(turn1, record1);
        assertFalse(sinkNeedsApproval(policy, turn1));
        policy.gateFor(turn1, UUID.randomUUID(), "s").afterCall("fetch_web_page", "{}", "poison");
        assertTrue(sinkNeedsApproval(policy, turn1));

        // Turn 2 of the same conversation starts tainted, before any tool ran.
        AtomicInteger turn2 = new AtomicInteger();
        TurnTaint record2 = new TurnTaint();
        conversations.attach(record2, "t1", "conv");
        policy.bind(turn2, record2);
        assertTrue(sinkNeedsApproval(policy, turn2));

        // A different conversation, and the same id under another tenant, start clean.
        for (String[] other : new String[][] {{"t1", "other"}, {"t2", "conv"}}) {
            AtomicInteger key = new AtomicInteger();
            TurnTaint record = new TurnTaint();
            conversations.attach(record, other[0], other[1]);
            policy.bind(key, record);
            assertFalse(sinkNeedsApproval(policy, key), other[0] + "/" + other[1]);
        }
    }
}
