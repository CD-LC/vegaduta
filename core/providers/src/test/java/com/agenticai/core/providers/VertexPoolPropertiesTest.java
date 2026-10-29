package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code vertex.pool.*}: a slot is usable only when it names a project AND has
 * a credential source (a key-file path or Application Default Credentials) -
 * the "blank means unconfigured" posture the class javadoc documents. The
 * provider-side view orders usable slots lowest-priority-first and honours
 * the master kill switch.
 */
class VertexPoolPropertiesTest {

    private static VertexPoolProperties.Slot slot(String projectId) {
        VertexPoolProperties.Slot slot = new VertexPoolProperties.Slot();
        slot.setProjectId(projectId);
        return slot;
    }

    @Test
    void aFreshSlotIsNotUsable() {
        VertexPoolProperties.Slot slot = new VertexPoolProperties.Slot();
        assertFalse(slot.isUsable(), "no project, no credential source");
        // Defaults: the documented fallbacks, asserted so a config-drift
        // refactor cannot silently change them.
        assertEquals("us-central1", slot.getLocation());
        assertTrue(slot.isSupportsText());
        assertTrue(slot.isSupportsImage());
        assertFalse(slot.isSupportsVideo());
        assertEquals(0, slot.getPriority());
    }

    @Test
    void aProjectWithoutACredentialSourceIsStillNotUsable() {
        assertFalse(slot("p-1").isUsable());
        VertexPoolProperties.Slot blankPath = slot("p-1");
        blankPath.setServiceAccountKeyPath("  ");
        assertFalse(blankPath.isUsable(), "a blank key path is no credential source");
    }

    @Test
    void aProjectPlusEitherCredentialSourceIsUsable() {
        VertexPoolProperties.Slot keyFile = slot("p-1");
        keyFile.setServiceAccountKeyPath("/secrets/gcp-key.json");
        assertTrue(keyFile.isUsable());

        VertexPoolProperties.Slot adc = slot("p-2");
        adc.setUseApplicationDefaultCredentials(true);
        assertTrue(adc.isUsable(), "ADC needs no key-file path");
    }

    @Test
    void aBlankProjectIdIsNeverUsable() {
        VertexPoolProperties.Slot slot = slot("   ");
        slot.setUseApplicationDefaultCredentials(true);
        assertFalse(slot.isUsable());
    }

    @Test
    void usablePoolSlotsAreLowestPriorityFirstAndNeedTheMasterSwitch() {
        VertexPoolProperties pool = new VertexPoolProperties();
        assertFalse(pool.isEnabled());
        VertexPoolProperties.Slot high = slot("high");
        high.setUseApplicationDefaultCredentials(true);
        high.setPriority(10);
        VertexPoolProperties.Slot low = slot("low");
        low.setUseApplicationDefaultCredentials(true);
        low.setPriority(1);
        VertexPoolProperties.Slot incomplete = slot(null);
        pool.setSlots(List.of(high, incomplete, low));

        VertexAccessTokenProvider tokens = new VertexAccessTokenProvider(pool);
        assertTrue(tokens.usablePoolSlots().isEmpty(), "master switch off - nothing is usable");
        assertFalse(tokens.isPoolConfigured());

        pool.setEnabled(true);
        assertEquals(List.of(low, high), tokens.usablePoolSlots());
        assertTrue(tokens.isPoolConfigured());
    }
}
