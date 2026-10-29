package com.agenticai.core.agents.tools.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link BuiltInToolClassificationsTail} is the media-onwards tail of the compiled-in declaration
 * table. These assert the entries a future edit could plausibly get wrong - the money-moving and
 * browser-acting sinks versus the generation tools that persist an artifact but deliver nothing.
 */
class BuiltInToolClassificationsTailTest {

    private static Map<String, ToolClassification> declared() {
        Map<String, ToolClassification> exact = new HashMap<>();
        BuiltInToolClassificationsTail.declare(exact);
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
    void generationToolsAreDurableTenantWritesThatAreNotEgress() {
        // Each call spends provider money and persists an artifact (DURABLE, never a blind retry),
        // but the artifact goes nowhere until forward_attachment sends it - so these are NOT
        // EGRESS and not irreversible.
        for (String name : new String[] {"generate_image", "generate_video", "generate_document",
                "generate_music", "speak"}) {
            ToolClassification c = declared().get(name);
            assertNotNull(c, name);
            assertEquals(ToolTrustTier.TENANT_SCOPED, c.trust(), name);
            assertEquals(ToolSensitivity.WRITE_TENANT, c.sensitivity(), name);
            assertEquals(ToolDeterminism.DURABLE, c.determinism(), name);
            assertFalse(c.irreversible(), name);
        }
    }

    @Test
    void createPaymentLinkIsCredentialedAndIrreversible() {
        // It moves money - the irreversible flag is what makes the gate ask a human, not the
        // tool description the model may never have read.
        ToolClassification c = declared().get("create_payment_link");

        assertNotNull(c);
        assertEquals(ToolTrustTier.TENANT_SCOPED, c.trust());
        assertEquals(ToolSensitivity.CREDENTIALED, c.sensitivity());
        assertEquals(ToolDeterminism.DURABLE, c.determinism());
        assertTrue(c.irreversible());
    }

    @Test
    void checkPaymentStatusIsAPlainCredentialedRead() {
        ToolClassification c = declared().get("check_payment_status");

        assertNotNull(c);
        assertEquals(ToolSensitivity.CREDENTIALED, c.sensitivity());
        assertFalse(c.irreversible());
    }

    @Test
    void browserConfirmClickIsAnIrreversibleEgressAction() {
        // It clicks a confirmation on a live third-party site: caller-influenced destination,
        // cannot be undone.
        ToolClassification c = declared().get("browser_confirm_click");

        assertNotNull(c);
        assertEquals(ToolTrustTier.UNTRUSTED_EXTERNAL, c.trust());
        assertEquals(ToolSensitivity.EGRESS, c.sensitivity());
        assertEquals(ToolDeterminism.DURABLE, c.determinism());
        assertTrue(c.irreversible());
    }

    @Test
    void browserRequestUserOnlyPausesAndReads() {
        // Asking the person to take over moves nothing and changes nothing on the site; its result
        // is third-party page text, so it is a taint source but no sink.
        ToolClassification c = declared().get("browser_request_user");

        assertNotNull(c);
        assertEquals(ToolTrustTier.UNTRUSTED_EXTERNAL, c.trust());
        assertEquals(ToolSensitivity.READ_PUBLIC, c.sensitivity());
        assertFalse(c.irreversible());
    }
}
