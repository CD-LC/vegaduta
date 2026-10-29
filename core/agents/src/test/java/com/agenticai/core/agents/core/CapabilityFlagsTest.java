package com.agenticai.core.agents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link CapabilityFlags} is the per-agent opt-in record embedded in {@link Agent}. These pin that
 * every flag is off in a fresh instance and that each {@code with...} returns a modified copy that
 * leaves the original - and every other flag on the copy - untouched.
 */
class CapabilityFlagsTest {

    @Test
    void noneStartsWithEveryFlagOff() {
        CapabilityFlags flags = CapabilityFlags.none();

        assertFalse(flags.isKnowledgeBaseEnabled());
        assertFalse(flags.isSarvamEnabled());
        assertFalse(flags.isE2bEnabled());
        assertFalse(flags.isXaiServerToolsEnabled());
        assertFalse(flags.isCodeWorkspaceEnabled());
        assertFalse(flags.isKnowledgeWriteEnabled());
        assertFalse(flags.isShareMemoryWithTenant());
        assertFalse(flags.isBiometricConsentEnabled());
    }

    @Test
    void eachWithSetsOnlyItsOwnFlagOnACopy() {
        CapabilityFlags flags = CapabilityFlags.none().withE2b(true);

        assertTrue(flags.isE2bEnabled());
        assertFalse(flags.isSarvamEnabled());
        assertFalse(flags.isCodeWorkspaceEnabled());

        CapabilityFlags more = flags.withKnowledgeWrite(true).withBiometricConsent(true);
        assertTrue(more.isE2bEnabled());
        assertTrue(more.isKnowledgeWriteEnabled());
        assertTrue(more.isBiometricConsentEnabled());
        // The intermediate object was not mutated - Hibernate's dirty check depends on this.
        assertFalse(flags.isKnowledgeWriteEnabled());
        assertNotSame(flags, more);
    }

    @Test
    void everyOptInHasItsOwnSwitch() {
        assertTrue(CapabilityFlags.none().withKnowledgeBase(true).isKnowledgeBaseEnabled());
        assertTrue(CapabilityFlags.none().withSarvam(true).isSarvamEnabled());
        assertTrue(CapabilityFlags.none().withXaiServerTools(true).isXaiServerToolsEnabled());
        assertTrue(CapabilityFlags.none().withCodeWorkspace(true).isCodeWorkspaceEnabled());
        assertTrue(CapabilityFlags.none().withShareMemoryWithTenant(true).isShareMemoryWithTenant());
    }

    @Test
    void equalityCoversEveryFlag() {
        CapabilityFlags a = CapabilityFlags.none().withSarvam(true);
        CapabilityFlags b = CapabilityFlags.none().withSarvam(true);
        CapabilityFlags c = CapabilityFlags.none().withE2b(true);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, CapabilityFlags.none());
        assertEquals(CapabilityFlags.none(), CapabilityFlags.none());
    }
}
