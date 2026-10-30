package com.agenticai.core.tenancy.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link DemoPersona}: the never-throws {@code fromParam} parse contract (anything unknown, blank
 * or absent maps to empty - the sandbox must not be failable), the wire spelling persisted on
 * {@code demo_session.persona}, and that every persona carries its three prompt assets.
 */
class DemoPersonaTest {

    @Test
    void fromParamParsesCaseInsensitively() {
        assertEquals(Optional.of(DemoPersona.KIRANA), DemoPersona.fromParam("kirana"));
        assertEquals(Optional.of(DemoPersona.KIRANA), DemoPersona.fromParam("KIRANA"));
        assertEquals(Optional.of(DemoPersona.KIRANA), DemoPersona.fromParam("  Kirana  "));
        assertEquals(Optional.of(DemoPersona.CA_OFFICE), DemoPersona.fromParam("ca_office"));
    }

    @Test
    void fromParamMapsAnythingUnknownBlankOrAbsentToEmptyNeverAnError() {
        assertTrue(DemoPersona.fromParam(null).isEmpty());
        assertTrue(DemoPersona.fromParam("").isEmpty());
        assertTrue(DemoPersona.fromParam("   ").isEmpty());
        assertTrue(DemoPersona.fromParam("spaceship").isEmpty());
        assertTrue(DemoPersona.fromParam("kirana; DROP TABLE").isEmpty());
    }

    @Test
    void wireNameIsTheLowercaseEnumSpelling() {
        assertEquals("kirana", DemoPersona.KIRANA.wireName());
        assertEquals("ca_office", DemoPersona.CA_OFFICE.wireName());
        assertEquals("mobile_repair", DemoPersona.MOBILE_REPAIR.wireName());
        // And wireName round-trips through fromParam for every persona.
        for (DemoPersona persona : DemoPersona.values()) {
            assertEquals(Optional.of(persona), DemoPersona.fromParam(persona.wireName()));
        }
    }

    @Test
    void everyPersonaCarriesALabelAnAgentNameAndAPromptBlock() {
        assertEquals(16, DemoPersona.values().length);
        for (DemoPersona persona : DemoPersona.values()) {
            assertFalse(persona.businessLabel().isBlank(), persona.name());
            assertFalse(persona.defaultAgentName().isBlank(), persona.name());
            assertFalse(persona.promptBlock().isBlank(), persona.name());
        }
    }

    @Test
    void regulatedPersonasCarryTheSameHardNoAdviceGuardTheClinicDoes() {
        assertTrue(DemoPersona.CLINIC.promptBlock().contains("must NOT give medical advice"));
        assertTrue(DemoPersona.PHARMACY.promptBlock().contains("must NOT recommend medicines"));
        assertTrue(DemoPersona.CA_OFFICE.promptBlock().contains("must NOT give tax advice"));
    }
}
