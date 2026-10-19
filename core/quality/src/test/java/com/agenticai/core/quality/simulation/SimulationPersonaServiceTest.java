package com.agenticai.core.quality.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link SimulationPersonaService} - the built-in/custom persona merge that
 * {@link SimulationRunService#createSession} depends on to resolve a
 * caller's persona keys into something it can actually drive a run from.
 */
class SimulationPersonaServiceTest {

    private SimulationPersonaRepository personaRepository;
    private SimulationPersonaService service;

    @BeforeEach
    void setUp() {
        personaRepository = mock(SimulationPersonaRepository.class);
        service = new SimulationPersonaService(personaRepository);
        when(personaRepository.findByKey(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
    }

    @Test
    void thereAreAtLeastSixBuiltInPersonasCoveringDistinctKeys() {
        assertTrue(SimulationPersonaService.BUILT_INS.size() >= 6);
        long distinctKeys = SimulationPersonaService.BUILT_INS.stream()
            .map(SimulationPersonaService.BuiltInPersona::key)
            .distinct()
            .count();
        assertEquals(SimulationPersonaService.BUILT_INS.size(), distinctKeys, "built-in persona keys must be unique");
    }

    @Test
    void listAllIncludesEveryBuiltInPlusEveryCustomPersona() {
        SimulationPersona custom = new SimulationPersona(
            "custom_one", "Custom One", "a tenant-authored persona", "achieve X", "tester");
        when(personaRepository.findAllByOrderByNameAsc()).thenReturn(List.of(custom));

        List<SimulationPersonaService.ResolvedPersona> all = service.listAll();

        assertEquals(SimulationPersonaService.BUILT_INS.size() + 1, all.size());
        assertTrue(all.stream().anyMatch(p -> p.key().equals("custom_one") && !p.builtIn()));
        assertTrue(all.stream().filter(p -> p.builtIn()).count() == SimulationPersonaService.BUILT_INS.size());
    }

    @Test
    void resolvePersonasReturnsEmptyForNullOrEmptyKeys() {
        assertEquals(List.of(), service.resolvePersonas(null));
        assertEquals(List.of(), service.resolvePersonas(List.of()));
    }

    @Test
    void resolvePersonasResolvesBuiltInKeysByDefault() {
        String key = SimulationPersonaService.BUILT_INS.get(0).key();
        when(personaRepository.findByKeyIn(List.of(key))).thenReturn(List.of());

        List<SimulationPersonaService.ResolvedPersona> resolved = service.resolvePersonas(List.of(key));

        assertEquals(1, resolved.size());
        assertEquals(key, resolved.get(0).key());
        assertTrue(resolved.get(0).builtIn());
    }

    @Test
    void resolvePersonasDropsUnknownKeysSilentlyRatherThanThrowing() {
        when(personaRepository.findByKeyIn(List.of("no-such-key"))).thenReturn(List.of());

        List<SimulationPersonaService.ResolvedPersona> resolved = service.resolvePersonas(List.of("no-such-key"));

        assertEquals(List.of(), resolved);
    }

    @Test
    void resolvePersonasPrefersACustomPersonaOverABuiltInWithTheSameKey() {
        String key = SimulationPersonaService.BUILT_INS.get(0).key(); // e.g. "prompt_injection"
        SimulationPersona override = new SimulationPersona(
            key, "Tenant Override", "a sharper domain-specific description", "a sharper domain-specific objective", "tester");
        when(personaRepository.findByKeyIn(List.of(key))).thenReturn(List.of(override));

        List<SimulationPersonaService.ResolvedPersona> resolved = service.resolvePersonas(List.of(key));

        assertEquals(1, resolved.size());
        assertEquals("Tenant Override", resolved.get(0).name());
        assertFalse(resolved.get(0).builtIn());
    }

    @Test
    void resolvePersonasPreservesRequestedOrderAcrossAMixOfBuiltInAndCustom() {
        String builtInKey = SimulationPersonaService.BUILT_INS.get(1).key();
        SimulationPersona custom = new SimulationPersona(
            "custom_two", "Custom Two", "description", "objective", "tester");
        when(personaRepository.findByKeyIn(List.of("custom_two", builtInKey))).thenReturn(List.of(custom));

        List<SimulationPersonaService.ResolvedPersona> resolved = service.resolvePersonas(List.of("custom_two", builtInKey));

        assertEquals(2, resolved.size());
        assertEquals("custom_two", resolved.get(0).key());
        assertEquals(builtInKey, resolved.get(1).key());
    }

    // -- createCustom validation --

    @Test
    void createCustomRejectsBlankFields() {
        assertThrows(IllegalArgumentException.class, () -> service.createCustom("", "name", "desc", "objective"));
        assertThrows(IllegalArgumentException.class, () -> service.createCustom("key", "", "desc", "objective"));
        assertThrows(IllegalArgumentException.class, () -> service.createCustom("key", "name", "", "objective"));
        assertThrows(IllegalArgumentException.class, () -> service.createCustom("key", "name", "desc", ""));
    }

    @Test
    void createCustomRejectsADuplicateKey() {
        when(personaRepository.findByKey("dup")).thenReturn(Optional.of(
            new SimulationPersona("dup", "Existing", "d", "o", "tester")));

        assertThrows(IllegalArgumentException.class, () -> service.createCustom("dup", "New", "d", "o"));
    }
}
