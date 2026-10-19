package com.agenticai.core.agents.tools.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.tools.context.RestoreToolResultToolCallback;
import org.junit.jupiter.api.Test;

/**
 * restore_tool_result is declared (fail-closed table) under the name the callback actually uses.
 */
class BuiltInToolClassificationsRestoreToolTest {

    @Test
    void theToolIsClassifiedUnderItsCallbackNameInTheSameCommit() {
        ToolClassification declared =
                BuiltInToolClassifications.all().get(RestoreToolResultToolCallback.NAME);

        assertNotNull(declared, "an unclassified first-party tool is blocked in every tenant");
        assertEquals(RestoreToolResultToolCallback.NAME, declared.toolName());
        assertEquals("restore_tool_result", RestoreToolResultToolCallback.NAME);
    }

    @Test
    void itIsATenantScopedReadWithNoConfirmationAndNeedsToolContext() {
        ToolClassification declared =
                BuiltInToolClassifications.all().get(RestoreToolResultToolCallback.NAME);

        assertEquals(ToolSensitivity.READ_TENANT, declared.sensitivity());
        assertTrue(declared.needsToolContext());
        assertFalse(declared.irreversible());
        assertFalse(declared.requiresHumanConfirmation());
        assertFalse(declared.determinism() == ToolDeterminism.PURE);
        assertNull(declared.requiredScope());
    }

    @Test
    void aRestoredResultStaysUntrustedBecauseItMayBeWhatTheWebReturned() {
        ToolClassification declared =
                BuiltInToolClassifications.all().get(RestoreToolResultToolCallback.NAME);

        assertEquals(ToolTrustTier.UNTRUSTED_EXTERNAL, declared.trust());
        assertFalse(
                declared.sensitivity().isTaintSink(),
                "reading is not a taint sink; only the trust tier carries taint");
    }
}
