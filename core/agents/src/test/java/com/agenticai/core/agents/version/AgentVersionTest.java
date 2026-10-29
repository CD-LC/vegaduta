package com.agenticai.core.agents.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentVersion} rows are immutable snapshots whose only mutable part is the label pointer.
 * These pin that {@code snapshot} stamps its own hash and timestamp, that an over-long change
 * summary is trimmed to the column width rather than rejected, and that the label moves only
 * through the two package-private mutators {@link AgentVersionService} drives.
 */
class AgentVersionTest {

    @Test
    void snapshotCapturesTheBundleAndComputesItsHash() {
        UUID agentId = UUID.randomUUID();
        String configJson = "{\"provider\":\"ANTHROPIC\",\"model\":\"claude-opus-4-8\"}";

        AgentVersion v =
            AgentVersion.snapshot(agentId, 3, configJson, "Helper", "ANTHROPIC", "claude-opus-4-8",
                "Model change", "user-sub");

        assertEquals(agentId, v.getAgentId());
        assertEquals(3, v.getVersionNumber());
        assertEquals(configJson, v.getConfigJson());
        assertEquals("Helper", v.getAgentName());
        assertEquals("ANTHROPIC", v.getProviderName());
        assertEquals("claude-opus-4-8", v.getModel());
        assertEquals("Model change", v.getChangeSummary());
        assertEquals("user-sub", v.getCreatedBy());
        assertNull(v.getLabel());
        assertNotNull(v.getCreatedAt(), "snapshot stamps its own time - a caller cannot backdate it");
        // The hash is computed inside the factory so no writer can forget it.
        assertEquals(VersionHashing.sha256Hex(configJson), v.getBundleHash());
    }

    @Test
    void anOverLongChangeSummaryIsTrimmedNotRejected() {
        String longSummary = "x".repeat(600);

        AgentVersion v =
            AgentVersion.snapshot(UUID.randomUUID(), 1, "{}", "n", "p", "m", longSummary, null);

        assertEquals(500, v.getChangeSummary().length());
        // Exactly at the limit and null both pass through untouched.
        assertEquals("y".repeat(500),
            AgentVersion.snapshot(UUID.randomUUID(), 1, "{}", "n", "p", "m", "y".repeat(500), null)
                .getChangeSummary());
        assertNull(
            AgentVersion.snapshot(UUID.randomUUID(), 1, "{}", "n", "p", "m", null, null)
                .getChangeSummary());
    }

    @Test
    void theLabelIsTheOnlyMutableField() {
        AgentVersion v =
            AgentVersion.snapshot(UUID.randomUUID(), 1, "{}", "n", "p", "m", "s", null);

        v.assignLabel(AgentVersionLabel.PRODUCTION);
        assertEquals(AgentVersionLabel.PRODUCTION, v.getLabel());

        v.assignLabel(AgentVersionLabel.STAGING);
        assertEquals(AgentVersionLabel.STAGING, v.getLabel());

        v.clearLabel();
        assertNull(v.getLabel());
    }
}
