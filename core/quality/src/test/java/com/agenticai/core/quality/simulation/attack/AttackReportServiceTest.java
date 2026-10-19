package com.agenticai.core.quality.simulation.attack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.simulation.SimulationDtos.AttackReportResponse;
import com.agenticai.core.quality.simulation.SimulationDtos.AttackScenarioResult;
import com.agenticai.core.quality.simulation.SimulationPersonaService;
import com.agenticai.core.quality.simulation.SimulationRun;
import com.agenticai.core.quality.simulation.SimulationRunService;
import com.agenticai.core.quality.simulation.SimulationSessionNotFoundException;
import com.agenticai.core.quality.simulation.SimulationTermination;
import com.agenticai.core.quality.simulation.SimulationVerdict;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class AttackReportServiceTest {

    private static final UUID SESSION = UUID.randomUUID();

    private static SimulationRun queued(String key) {
        // Built-in attack runs carry the catalog scenario's own name (the report checks it).
        String name = AttackScenarioCatalog.find(key).map(AttackScenarioCatalog.Scenario::name).orElse("Name of " + key);
        SimulationRun run = new SimulationRun(SESSION, key, name);
        ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
        return run;
    }

    private static SimulationRun finished(String key, SimulationVerdict verdict, String reason) {
        SimulationRun run = queued(key);
        run.markRunning();
        run.complete("[]", 1, verdict, reason, SimulationTermination.MAX_TURNS);
        return run;
    }

    @Test
    void rateExcludesInconclusiveAndCountsOnlyAttackScenarioRuns() {
        SimulationRun leaked = finished("attack_poisoned_email", SimulationVerdict.FAILED, "canary leaked in turn 1");
        SimulationRun judgedFailed = finished("attack_poisoned_web_page", SimulationVerdict.FAILED, "It obeyed.");
        SimulationRun resisted = finished("attack_poisoned_document", SimulationVerdict.PASSED, "It refused.");
        SimulationRun unsure = finished(
            "attack_poisoned_mcp_tool_description", SimulationVerdict.INCONCLUSIVE, "Judge unavailable");
        SimulationRun ordinaryPersona = finished("prompt_injection", SimulationVerdict.FAILED, "not an attack run");

        AttackReportResponse report = AttackReportService.build(
            SESSION, List.of(leaked, judgedFailed, resisted, unsure, ordinaryPersona));

        assertEquals(SESSION, report.sessionId());
        assertEquals(4, report.total());
        assertEquals(2, report.succeededAttacks());
        assertEquals(1, report.resisted());
        assertEquals(1, report.inconclusive());
        assertEquals(0, report.pending());
        assertEquals(2.0 / 3.0, report.attackSuccessRate(), 1e-9);
        assertEquals(4, report.perScenario().size());
        assertTrue(report.limits().get(0).startsWith("tools disabled"));

        AttackScenarioResult first = report.perScenario().get(0);
        assertEquals("attack_poisoned_email", first.key());
        assertEquals(SimulationVerdict.FAILED, first.verdict());
        assertTrue(first.deterministic());
        assertEquals(leaked.getId(), first.runId());
        assertFalse(report.perScenario().get(1).deterministic(), "judge-decided failures are not deterministic");
        assertFalse(report.perScenario().get(2).deterministic());
    }

    @Test
    void unfinishedRunsArePendingAndErroredOrCancelledRunsAreInconclusive() {
        SimulationRun stillQueued = queued("attack_poisoned_email");
        SimulationRun errored = queued("attack_poisoned_web_page");
        errored.fail("provider down");
        SimulationRun cancelled = queued("attack_poisoned_document");
        cancelled.markCancelled("Cancelled by request.");

        AttackReportResponse report = AttackReportService.build(SESSION, List.of(stillQueued, errored, cancelled));

        assertEquals(3, report.total());
        assertEquals(1, report.pending());
        assertEquals(2, report.inconclusive());
        assertEquals(0, report.succeededAttacks());
        assertNull(report.attackSuccessRate(), "no decided run = no rate, never 0%");
        assertNull(report.perScenario().get(0).verdict());
        assertEquals(SimulationVerdict.INCONCLUSIVE, report.perScenario().get(1).verdict());
        assertTrue(report.perScenario().get(1).reason().contains("provider down"));
        assertEquals(SimulationVerdict.INCONCLUSIVE, report.perScenario().get(2).verdict());
    }

    @Test
    void sessionWithoutAttackRunsYieldsAnEmptyReport() {
        AttackReportResponse report = AttackReportService.build(
            SESSION, List.of(finished("prompt_injection", SimulationVerdict.PASSED, "fine")));

        assertEquals(0, report.total());
        assertNull(report.attackSuccessRate());
        assertTrue(report.perScenario().isEmpty());
    }

    @Test
    void customPersonaReusingAnAttackKeyIsNotCounted() {
        // A tenant persona created before the prefix was reserved: same key, its own name.
        SimulationRun custom = new SimulationRun(SESSION, "attack_poisoned_email", "My own persona");
        ReflectionTestUtils.setField(custom, "id", UUID.randomUUID());
        custom.markRunning();
        custom.complete("[]", 1, SimulationVerdict.FAILED, "It misbehaved.", SimulationTermination.MAX_TURNS);

        AttackReportResponse report = AttackReportService.build(SESSION, List.of(
            custom, finished("attack_poisoned_document", SimulationVerdict.PASSED, "ok")));

        assertEquals(1, report.total());
        assertEquals(0, report.succeededAttacks());
        assertEquals(1, report.resisted());
        assertEquals(0.0, report.attackSuccessRate(), 1e-9);
        assertEquals("attack_poisoned_document", report.perScenario().get(0).key());
    }

    @Test
    void allResistedIsZeroPercent() {
        AttackReportResponse report = AttackReportService.build(SESSION, List.of(
            finished("attack_poisoned_email", SimulationVerdict.PASSED, "ok"),
            finished("attack_poisoned_document", SimulationVerdict.PASSED, "ok")));

        assertEquals(0.0, report.attackSuccessRate(), 1e-9);
    }

    @Test
    void reportIsNotFoundWhenTheFlagIsOffAndNeverTouchesRuns() {
        SimulationRunService runService = mock(SimulationRunService.class);
        SimulationPersonaService personaService = mock(SimulationPersonaService.class);
        when(personaService.attackScenariosEnabled()).thenReturn(false);
        UUID agentId = UUID.randomUUID();

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> new AttackReportService(runService, personaService).report(agentId, SESSION));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(runService, never()).listRuns(agentId, SESSION);
    }

    @Test
    void reportReadsRunsThroughTheAgentScopedLookup() {
        SimulationRunService runService = mock(SimulationRunService.class);
        SimulationPersonaService personaService = mock(SimulationPersonaService.class);
        when(personaService.attackScenariosEnabled()).thenReturn(true);
        UUID agentId = UUID.randomUUID();
        when(runService.listRuns(agentId, SESSION)).thenReturn(
            List.of(finished("attack_poisoned_email", SimulationVerdict.FAILED, "canary leaked in turn 2")));

        AttackReportResponse report = new AttackReportService(runService, personaService).report(agentId, SESSION);

        assertEquals(1, report.succeededAttacks());
        assertEquals(1.0, report.attackSuccessRate(), 1e-9);

        UUID otherAgent = UUID.randomUUID();
        when(runService.listRuns(otherAgent, SESSION)).thenThrow(new SimulationSessionNotFoundException(SESSION));
        assertThrows(SimulationSessionNotFoundException.class,
            () -> new AttackReportService(runService, personaService).report(otherAgent, SESSION));
    }
}
