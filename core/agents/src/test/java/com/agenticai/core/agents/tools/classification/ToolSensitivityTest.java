package com.agenticai.core.agents.tools.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ToolSensitivity} is the capability half of a tool's classification. The two derived
 * predicates are the single definitions of "taint sink" and "consequential" the gates consume -
 * reads are deliberately neither, because gating a read on taint would break the
 * fetch-a-page-then-look-something-up shape; every mutating, credentialed or outbound tier is both.
 */
class ToolSensitivityTest {

    @Test
    void allFiveTiersExist() {
        assertEquals(5, ToolSensitivity.values().length);
    }

    @Test
    void readsAreNotTaintSinks() {
        assertFalse(ToolSensitivity.READ_PUBLIC.isTaintSink());
        assertFalse(ToolSensitivity.READ_TENANT.isTaintSink());
    }

    @Test
    void writesCredentialsAndEgressAreTaintSinks() {
        assertTrue(ToolSensitivity.WRITE_TENANT.isTaintSink());
        assertTrue(ToolSensitivity.CREDENTIALED.isTaintSink());
        assertTrue(ToolSensitivity.EGRESS.isTaintSink());
    }

    @Test
    void consequentialMatchesTheSinkTiers() {
        for (ToolSensitivity tier : ToolSensitivity.values()) {
            assertEquals(tier.isTaintSink(), tier.isConsequential(),
                tier + ": today the two questions have the same answer, and both are exhaustive switches");
        }
    }
}
