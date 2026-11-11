package com.agenticai.core.agents.tools.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link BuiltInToolClassificationsExtended} is the messaging-onwards half of the compiled-in
 * declaration table. These assert the entries whose classification a reader could plausibly get
 * wrong on a future edit - the EGRESS/irreversible sinks and the deliberate non-taint-source
 * exceptions - rather than restating the whole table (the invariant tests on {@link
 * BuiltInToolClassifications} cover that).
 */
class BuiltInToolClassificationsExtendedTest {

    private static Map<String, ToolClassification> declared() {
        Map<String, ToolClassification> exact = new HashMap<>();
        BuiltInToolClassificationsExtended.declare(exact);
        return exact;
    }

    @Test
    void declareAppendsEveryEntryKeyedByItsOwnName() {
        Map<String, ToolClassification> exact = declared();

        assertFalse(exact.isEmpty());
        for (Map.Entry<String, ToolClassification> entry : exact.entrySet()) {
            assertEquals(entry.getKey(), entry.getValue().toolName());
        }
    }

    @Test
    void forwardAttachmentIsAnIrreversibleEgressSink() {
        // A delivered message cannot be recalled and the destination is caller-named: this is the
        // classification the taint gate exists for.
        ToolClassification c = declared().get("forward_attachment");

        assertNotNull(c);
        assertEquals(ToolSensitivity.EGRESS, c.sensitivity());
        assertEquals(ToolDeterminism.DURABLE, c.determinism());
        assertTrue(c.irreversible());
    }

    @Test
    void requestHumanIsTheSafeEscalationPath() {
        // Notifying a person is the one action that is safe to take while tainted - internal,
        // no external destination, not irreversible.
        ToolClassification c = declared().get("request_human");

        assertNotNull(c);
        assertEquals(ToolTrustTier.TRUSTED_INTERNAL, c.trust());
        assertEquals(ToolSensitivity.WRITE_TENANT, c.sensitivity());
        assertFalse(c.irreversible());
    }

    @Test
    void telegramHistoryReadsAreTaintSources() {
        // Anyone who can message the tenant's bot authored these bytes.
        ToolClassification c = declared().get("telegram_read_messages");

        assertNotNull(c);
        assertEquals(ToolTrustTier.UNTRUSTED_EXTERNAL, c.trust());
        assertEquals(ToolSensitivity.READ_TENANT, c.sensitivity());
        assertFalse(c.irreversible());
    }

    @Test
    void runCodeIsDeclaredEgressFailClosed() {
        // Nothing proves the sandbox has no outbound network, so the declaration assumes egress -
        // and a code execution call is irreversible by nature.
        ToolClassification c = declared().get("run_code");

        assertNotNull(c);
        assertEquals(ToolTrustTier.UNTRUSTED_EXTERNAL, c.trust());
        assertEquals(ToolSensitivity.EGRESS, c.sensitivity());
        assertEquals(ToolDeterminism.DURABLE, c.determinism());
        assertTrue(c.irreversible());
    }

    @Test
    void localShellReadStaysAPlainTenantRead() {
        // The allow-listed inspection commands change nothing: no approval, no taint-source tier.
        ToolClassification c = declared().get("local_shell_read");

        assertNotNull(c);
        assertEquals(ToolTrustTier.TENANT_SCOPED, c.trust());
        assertEquals(ToolSensitivity.READ_TENANT, c.sensitivity());
        assertFalse(c.irreversible());
    }
}
