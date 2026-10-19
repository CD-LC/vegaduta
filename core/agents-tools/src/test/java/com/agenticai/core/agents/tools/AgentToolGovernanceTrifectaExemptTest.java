package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationDecision;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.agents.tools.classification.ToolDeterminism;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.classification.ToolTrustTier;
import com.agenticai.core.guardrails.ToolDispatchGate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The confirmation-gate exempt list is for ordinary agents: an agent that holds the lethal trifecta
 * loses it ({@link TrifectaEnforcementService#exemptionHolds}), decided per agent on the dispatch
 * path.
 */
class AgentToolGovernanceTrifectaExemptTest {

    private static final String EXEMPT_TOOL = "channel_send_message";
    private static final String OTHER_TOOL = "mcp__crm__delete_contact";

    private final UUID agentId = UUID.randomUUID();
    private final ToolClassificationRegistry registry = mock(ToolClassificationRegistry.class);
    private final TrifectaEnforcementService trifecta = mock(TrifectaEnforcementService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void anExemptToolStaysExemptForAnOrdinaryAgent() {
        stubIrreversibleEgress(EXEMPT_TOOL);
        when(trifecta.exemptionHolds(agentId)).thenReturn(true);

        assertEquals(
                ToolDispatchGate.Outcome.ALLOW,
                governance(true).decide(registry, EXEMPT_TOOL, agentId).outcome());
    }

    @Test
    void anExemptToolNeedsApprovalForAnAgentHoldingTheTrifecta() {
        stubIrreversibleEgress(EXEMPT_TOOL);
        when(trifecta.exemptionHolds(agentId)).thenReturn(false);

        ToolDispatchGate.Verdict verdict = governance(true).decide(registry, EXEMPT_TOOL, agentId);

        assertEquals(ToolDispatchGate.Outcome.REQUIRE_APPROVAL, verdict.outcome());
        assertEquals(1.0, meters.counter(AgentToolGovernance.CONFIRMATION_ENFORCED_METRIC).count());
    }

    @Test
    void theGateReturnedForAnAgentCarriesThatAgentsId() {
        stubIrreversibleEgress(EXEMPT_TOOL);
        when(trifecta.exemptionHolds(agentId)).thenReturn(false);

        ToolDispatchGate gate = governance(true).dispatchGate(agentId);

        assertEquals(ToolDispatchGate.Outcome.REQUIRE_APPROVAL, gate.check(EXEMPT_TOOL).outcome());
        verify(trifecta).exemptionHolds(agentId);
    }

    @Test
    void theAgentlessGateIsUnchangedAndNeverAsksTheTrifectaService() {
        stubIrreversibleEgress(EXEMPT_TOOL);

        assertEquals(
                ToolDispatchGate.Outcome.ALLOW,
                governance(true).dispatchGate().check(EXEMPT_TOOL).outcome());
        assertEquals(
                ToolDispatchGate.Outcome.ALLOW,
                governance(true).decide(registry, EXEMPT_TOOL).outcome());
        verify(trifecta, never()).exemptionHolds(any());
    }

    @Test
    void aToolOutsideTheExemptListNeverPaysForTheTrifectaLookup() {
        stubIrreversibleEgress(OTHER_TOOL);

        assertEquals(
                ToolDispatchGate.Outcome.REQUIRE_APPROVAL,
                governance(true).decide(registry, OTHER_TOOL, agentId).outcome());
        verify(trifecta, never()).exemptionHolds(any());
    }

    @Test
    void withTheConfirmationGateOffNothingIsGatedAndTheLookupIsSkipped() {
        stubIrreversibleEgress(EXEMPT_TOOL);

        assertEquals(
                ToolDispatchGate.Outcome.ALLOW,
                governance(false).decide(registry, EXEMPT_TOOL, agentId).outcome());
        verify(trifecta, never()).exemptionHolds(any());
    }

    @Test
    void withoutATrifectaServiceTheExemptListAppliesToEveryAgentAsBefore() {
        stubIrreversibleEgress(EXEMPT_TOOL);
        AgentToolGovernance plain =
                new AgentToolGovernance(
                        providerOf(registry),
                        providerOf((MeterRegistry) meters),
                        providerOf(null),
                        true,
                        EXEMPT_TOOL);

        assertEquals(
                ToolDispatchGate.Outcome.ALLOW,
                plain.decide(registry, EXEMPT_TOOL, agentId).outcome());
    }

    private void stubIrreversibleEgress(String tool) {
        when(registry.resolve(tool))
                .thenReturn(
                        new ToolClassificationDecision.Classified(
                                new ToolClassification(
                                        tool,
                                        ToolTrustTier.TENANT_SCOPED,
                                        ToolSensitivity.EGRESS,
                                        ToolDeterminism.DURABLE,
                                        null,
                                        true,
                                        true)));
    }

    private AgentToolGovernance governance(boolean confirmationGate) {
        AgentToolGovernance governance =
                new AgentToolGovernance(
                        providerOf(registry),
                        providerOf((MeterRegistry) meters),
                        providerOf(null),
                        confirmationGate,
                        EXEMPT_TOOL);
        governance.setTrifectaEnforcement(trifecta);
        return governance;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
