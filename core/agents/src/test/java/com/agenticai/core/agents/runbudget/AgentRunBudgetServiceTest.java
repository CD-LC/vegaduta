package com.agenticai.core.agents.runbudget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentRunBudgetResponse;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AgentRunBudgetServiceTest {

    private final AgentService agentService = mock(AgentService.class);
    private final UUID agentId = UUID.randomUUID();

    // $1 / $5 per M tokens, 10_000 avg tokens/branch => $0.018 per branch (see RunBudgetTest).
    private AgentRunBudgetService service(String globalCap, boolean overrideEnabled) {
        return new AgentRunBudgetService(agentService, new BigDecimal(globalCap), BigDecimal.ONE,
            new BigDecimal("5"), 10_000, overrideEnabled, new BigDecimal("10"));
    }

    private void agentWithCap(String cap) {
        Agent agent = mock(Agent.class);
        when(agent.getRunBudgetUsd()).thenReturn(cap == null ? null : new BigDecimal(cap));
        when(agentService.requireAccess(agentId, PermissionLevel.VIEWER)).thenReturn(agent);
    }

    private static void assertNum(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void getReportsPlatformCapWhenAgentHasNone() {
        agentWithCap(null);
        AgentRunBudgetResponse r = service("2.0", true).get(agentId);

        assertTrue(r.overrideEnabled());
        assertNum("2", r.globalCapUsd());
        assertNull(r.agentCapUsd());
        assertNum("2", r.effectiveCapUsd());
        assertNum("2", r.maxAgentCapUsd());
        assertNum("0.018", r.perBranchEstimateUsd());
        assertEquals("delegation tools only", r.scope());
        assertTrue(r.estimate());
    }

    @Test
    void getReportsTheLowerAgentCapAsEffective() {
        agentWithCap("0.5000");
        AgentRunBudgetResponse r = service("2.0", true).get(agentId);

        assertNum("0.5", r.agentCapUsd());
        assertNum("0.5", r.effectiveCapUsd());
    }

    @Test
    void getWithOverrideOffIgnoresTheAgentValue() {
        agentWithCap("0.5");
        AgentRunBudgetResponse r = service("2.0", false).get(agentId);

        assertFalse(r.overrideEnabled());
        assertNum("0.5", r.agentCapUsd());
        assertNum("2", r.effectiveCapUsd());
    }

    @Test
    void getIsDeniedWhenTheCallerCannotViewTheAgent() {
        when(agentService.requireAccess(agentId, PermissionLevel.VIEWER))
            .thenThrow(new ResourceAccessDeniedException());

        assertThrows(ResourceAccessDeniedException.class, () -> service("2.0", true).get(agentId));
    }

    @Test
    void updateStoresAValidatedValueThroughTheEditLockedPath() {
        AgentRunBudgetResponse r = service("2.0", true).update(agentId, new BigDecimal("1.5"));

        verify(agentService).setRunBudgetUsd(agentId, new BigDecimal("1.5000"));
        assertNum("1.5", r.agentCapUsd());
        assertNum("1.5", r.effectiveCapUsd());
    }

    @Test
    void updateWithNullClearsTheCap() {
        AgentRunBudgetResponse r = service("2.0", true).update(agentId, null);

        verify(agentService).setRunBudgetUsd(agentId, null);
        assertNull(r.agentCapUsd());
        assertNum("2", r.effectiveCapUsd());
    }

    @Test
    void updateAboveThePlatformCapIsRejectedBeforeAnythingIsSaved() {
        assertThrows(IllegalArgumentException.class,
            () -> service("2.0", true).update(agentId, new BigDecimal("2.5")));
        verify(agentService, never()).setRunBudgetUsd(any(), any());
    }

    @Test
    void updateIsAConflictWhenTheOverrideIsSwitchedOff() {
        agentWithCap(null);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> service("2.0", false).update(agentId, new BigDecimal("1")));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verify(agentService, never()).setRunBudgetUsd(any(), any());
    }

    @Test
    void updateChecksAccessBeforeValidationSoAnUnseenAgentIsNeverA400() {
        when(agentService.requireAccess(agentId, PermissionLevel.VIEWER))
            .thenThrow(new ResourceAccessDeniedException());

        // 2.5 is above the platform cap: without the access check first this would be a 400.
        assertThrows(ResourceAccessDeniedException.class,
            () -> service("2.0", true).update(agentId, new BigDecimal("2.5")));
        verify(agentService, never()).setRunBudgetUsd(any(), any());
    }

    @Test
    void controllerDelegatesAndClearsOnlyOnAnExplicitNull() throws Exception {
        AgentRunBudgetService svc = mock(AgentRunBudgetService.class);
        AgentRunBudgetController controller = new AgentRunBudgetController(svc);
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

        controller.get(agentId);
        controller.update(agentId, json.readTree("{\"runBudgetUsd\": 1.5}"));
        controller.update(agentId, json.readTree("{\"runBudgetUsd\": null}"));

        verify(svc).get(agentId);
        verify(svc).update(agentId, new BigDecimal("1.5"));
        verify(svc).update(agentId, null);
    }

    @Test
    void controllerRejectsAMissingOrMisspelledFieldInsteadOfClearing() throws Exception {
        AgentRunBudgetService svc = mock(AgentRunBudgetService.class);
        AgentRunBudgetController controller = new AgentRunBudgetController(svc);
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

        assertThrows(IllegalArgumentException.class, () -> controller.update(agentId, null));
        assertThrows(IllegalArgumentException.class, () -> controller.update(agentId, json.readTree("{}")));
        assertThrows(IllegalArgumentException.class,
            () -> controller.update(agentId, json.readTree("{\"run_budget_usd\": 1}")));
        assertThrows(IllegalArgumentException.class,
            () -> controller.update(agentId, json.readTree("{\"runBudgetUsd\": true}")));
        assertThrows(IllegalArgumentException.class,
            () -> controller.update(agentId, json.readTree("{\"runBudgetUsd\": \"abc\"}")));
        assertThrows(IllegalArgumentException.class, () -> controller.update(agentId, json.readTree("[1]")));
        assertEquals(0, new BigDecimal("0.25").compareTo(
            AgentRunBudgetController.parseRunBudgetUsd(json.readTree("{\"runBudgetUsd\": \"0.25\"}"))));
        verify(svc, never()).update(any(), any());
    }
}
