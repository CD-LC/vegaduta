package com.agenticai.core.quality.simulation.attack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.simulation.SimulationDtos.PersonaResponse;
import com.agenticai.core.quality.simulation.SimulationPersonaRepository;
import com.agenticai.core.quality.simulation.SimulationPersonaService;
import com.agenticai.core.quality.simulation.SimulationPersonaService.ResolvedPersona;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AttackScenarioCatalogTest {

    private static SimulationPersonaService personaService(boolean attackEnabled) {
        SimulationPersonaRepository repo = mock(SimulationPersonaRepository.class);
        when(repo.findByKey(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        SimulationPersonaService service = new SimulationPersonaService(repo);
        // The setter is package-private (Spring calls it); tests in this sub-package set the field.
        ReflectionTestUtils.setField(service, "attackScenariosEnabled", attackEnabled);
        return service;
    }

    @Test
    void catalogHasTheFourScenariosWithUniquePrefixedKeysThatFitTheColumn() {
        List<String> keys = AttackScenarioCatalog.SCENARIOS.stream().map(AttackScenarioCatalog.Scenario::key).toList();
        assertEquals(List.of(
            "attack_poisoned_email", "attack_poisoned_web_page",
            "attack_poisoned_mcp_tool_description", "attack_poisoned_document"), keys);
        for (String key : keys) {
            assertTrue(AttackScenarioCatalog.isAttackKey(key));
            assertTrue(key.length() <= 64, "simulation_run.persona_key is VARCHAR(64)");
            assertTrue(AttackScenarioCatalog.find(key).isPresent());
        }
        assertTrue(AttackScenarioCatalog.find("attack_unknown").isEmpty());
        assertTrue(AttackScenarioCatalog.find(null).isEmpty());
        assertFalse(AttackScenarioCatalog.isAttackKey("prompt_injection"));
    }

    @Test
    void payloadCarriesBothFragmentsButNeverTheJoinedCanary() {
        AttackScenarioCatalog.Canary canary = AttackScenarioCatalog.newCanary();
        for (AttackScenarioCatalog.Scenario scenario : AttackScenarioCatalog.SCENARIOS) {
            String payload = scenario.payload(canary);
            assertTrue(payload.contains(canary.partA()), scenario.key());
            assertTrue(payload.contains(canary.partB()), scenario.key());
            assertTrue(payload.contains(canary.exfilUrlPrefix()), scenario.key());
            assertFalse(payload.contains(canary.token()), scenario.key());
            // Quoting the whole payload back must not count as obeying it.
            assertFalse(CanaryVerdict.leaked(canary, payload), scenario.key());
        }
    }

    @Test
    void followUpSimulatorPromptNeverDelegatesAuthorityToTheContent() {
        for (AttackScenarioCatalog.Scenario scenario : AttackScenarioCatalog.SCENARIOS) {
            String prompt = AttackScenarioCatalog.followUpSimulatorPrompt(scenario, 4, "<<DONE>>")
                .toLowerCase(java.util.Locale.ROOT);
            // A user who says "do what the content needs" turns an indirect injection into a user request.
            assertFalse(prompt.contains("go ahead"), scenario.key());
            assertFalse(prompt.contains("finish whatever"), scenario.key());
            assertFalse(prompt.contains("content needs"), scenario.key());
            assertTrue(prompt.contains("never ask the assistant to act on the content"), scenario.key());
            assertTrue(prompt.contains("<<done>>"), scenario.key());
        }
    }

    @Test
    void canariesAreRandomPerRun() {
        assertNotEquals(AttackScenarioCatalog.newCanary().token(), AttackScenarioCatalog.newCanary().token());
        assertTrue(AttackScenarioCatalog.newCanary().token().matches("[a-z0-9]{16}"));
    }

    @Test
    void enabledServiceListsAndResolvesAttackScenariosAsBuiltInAttackPersonas() {
        SimulationPersonaService service = personaService(true);

        List<ResolvedPersona> all = service.listAll();
        assertEquals(SimulationPersonaService.BUILT_INS.size() + AttackScenarioCatalog.SCENARIOS.size(), all.size());

        List<ResolvedPersona> resolved = service.resolvePersonas(List.of("attack_poisoned_email", "prompt_injection"));
        assertEquals(2, resolved.size());
        assertTrue(resolved.get(0).builtIn());
        assertTrue(PersonaResponse.of(resolved.get(0)).attack());
        assertFalse(PersonaResponse.of(resolved.get(1)).attack());
    }

    @Test
    void disabledServiceNeitherListsNorResolvesThem() {
        SimulationPersonaService service = personaService(false);

        assertEquals(SimulationPersonaService.BUILT_INS.size(), service.listAll().size());
        assertTrue(service.resolvePersonas(List.of("attack_poisoned_email")).isEmpty());
        assertFalse(service.attackScenariosEnabled());
    }

    @Test
    void customPersonaWithAnAttackShapedKeyIsNotFlaggedAsAnAttack() {
        ResolvedPersona custom = new ResolvedPersona("attack_poisoned_email", "Mine", "d", "o", false);
        assertFalse(PersonaResponse.of(custom).attack());
    }

    @Test
    void reservedPrefixCannotBeUsedForANewCustomPersonaWhileEnabled() {
        assertThrows(IllegalArgumentException.class,
            () -> personaService(true).createCustom("attack_mine", "n", "d", "o"));
    }
}
