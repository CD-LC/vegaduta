package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationDecision;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.agents.tools.classification.ToolDeterminism;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.classification.ToolTrustTier;
import com.agenticai.core.chat.gateway.LlmGatewayToolOutputRedactor;
import com.agenticai.core.guardrails.ToolDispatchGate;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * How the tool-classification registry's answer becomes a dispatch verdict: quarantined/invalid
 * tools are refused, unclassified tools still run (counted), and the irreversible-action
 * confirmation gate only fires behind its flag.
 */
class AgentToolGovernanceTest {

    private static final String TOOL = "mcp__crm__delete_contact";

    private final ToolClassificationRegistry registry = mock(ToolClassificationRegistry.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void quarantinedToolsAreRefused() {
        when(registry.resolve(TOOL))
                .thenReturn(
                        new ToolClassificationDecision.Blocked(
                                TOOL,
                                ToolClassificationDecision.Reason.QUARANTINED,
                                "manifest changed"));

        ToolDispatchGate.Verdict verdict = governance(false).decide(registry, TOOL);

        assertEquals(ToolDispatchGate.Outcome.DENY, verdict.outcome());
        assertTrue(verdict.reason().contains("quarantined"), verdict.reason());
        assertEquals(
                1.0,
                meters.counter(AgentToolGovernance.REFUSED_METRIC, "reason", "QUARANTINED")
                        .count());
    }

    @Test
    void invalidDeclarationsAreRefused() {
        when(registry.resolve(TOOL))
                .thenReturn(
                        new ToolClassificationDecision.Blocked(
                                TOOL,
                                ToolClassificationDecision.Reason.INVALID_DECLARATION,
                                "unknown trust tier"));

        assertEquals(
                ToolDispatchGate.Outcome.DENY, governance(false).decide(registry, TOOL).outcome());
    }

    @Test
    void unclassifiedToolsStillRunButAreCounted() {
        when(registry.resolve(TOOL))
                .thenReturn(
                        new ToolClassificationDecision.Blocked(
                                TOOL, ToolClassificationDecision.Reason.UNCLASSIFIED, "no row"));

        assertEquals(
                ToolDispatchGate.Outcome.ALLOW, governance(false).decide(registry, TOOL).outcome());
        assertEquals(1.0, meters.counter(AgentToolGovernance.UNCLASSIFIED_ALLOWED_METRIC).count());
    }

    @Test
    void irreversibleConsequentialToolsAreOnlyShadowCountedByDefault() {
        when(registry.resolve(TOOL)).thenReturn(irreversibleEgress());

        assertEquals(
                ToolDispatchGate.Outcome.ALLOW, governance(false).decide(registry, TOOL).outcome());
        assertEquals(1.0, meters.counter(AgentToolGovernance.CONFIRMATION_SHADOW_METRIC).count());
    }

    @Test
    void irreversibleConsequentialToolsNeedApprovalWhenTheGateIsOn() {
        when(registry.resolve(TOOL)).thenReturn(irreversibleEgress());

        assertEquals(
                ToolDispatchGate.Outcome.REQUIRE_APPROVAL,
                governance(true).decide(registry, TOOL).outcome());
    }

    @Test
    void gateOffIsAnOptOutThatOnlyCounts() {
        when(registry.resolve(TOOL)).thenReturn(irreversibleEgress());

        assertEquals(
                ToolDispatchGate.Outcome.ALLOW, governance(false).decide(registry, TOOL).outcome());
    }

    @Test
    void enforcedGateIsCountedAndExemptToolsStayAllowed() {
        when(registry.resolve(TOOL)).thenReturn(irreversibleEgress());
        when(registry.resolve("channel_send_message")).thenReturn(irreversibleEgress());
        AgentToolGovernance governance = governance(true);

        assertEquals(
                ToolDispatchGate.Outcome.REQUIRE_APPROVAL,
                governance.decide(registry, TOOL).outcome());
        assertEquals(1.0, meters.counter(AgentToolGovernance.CONFIRMATION_ENFORCED_METRIC).count());
        assertEquals(
                ToolDispatchGate.Outcome.ALLOW,
                governance.decide(registry, "channel_send_message").outcome());
    }

    @Test
    void a2aCallAgentIsGatedEvenWhenTheClassificationGateIsOffOrExempt() {
        when(registry.resolve("a2a_call_agent")).thenReturn(irreversibleEgress());

        assertEquals(
                ToolDispatchGate.Outcome.REQUIRE_APPROVAL,
                governance(false).decide(registry, "a2a_call_agent").outcome());
        AgentToolGovernance exempt =
                new AgentToolGovernance(
                        providerOf(registry),
                        providerOf((MeterRegistry) meters),
                        providerOf(null),
                        true,
                        "a2a_call_agent");
        assertEquals(
                ToolDispatchGate.Outcome.REQUIRE_APPROVAL,
                exempt.decide(registry, "a2a_call_agent").outcome());
    }

    @Test
    void unclassifiedA2aCallAgentStillNeedsApproval() {
        when(registry.resolve("a2a_call_agent"))
                .thenReturn(
                        new ToolClassificationDecision.Blocked(
                                "a2a_call_agent",
                                ToolClassificationDecision.Reason.UNCLASSIFIED,
                                "no row"));

        assertEquals(
                ToolDispatchGate.Outcome.REQUIRE_APPROVAL,
                governance(false).decide(registry, "a2a_call_agent").outcome());
    }

    @Test
    void exemptListParsingIgnoresBlankAndPaddedEntries() {
        when(registry.resolve("browser_click")).thenReturn(irreversibleEgress());
        when(registry.resolve("run_code")).thenReturn(irreversibleEgress());
        when(registry.resolve(TOOL)).thenReturn(irreversibleEgress());
        AgentToolGovernance governance =
                new AgentToolGovernance(
                        providerOf(registry),
                        providerOf((MeterRegistry) meters),
                        providerOf(null),
                        true,
                        " browser_click , ,, run_code ");

        assertEquals(
                ToolDispatchGate.Outcome.ALLOW,
                governance.decide(registry, "browser_click").outcome());
        assertEquals(
                ToolDispatchGate.Outcome.ALLOW, governance.decide(registry, "run_code").outcome());
        assertEquals(
                ToolDispatchGate.Outcome.REQUIRE_APPROVAL,
                governance.decide(registry, TOOL).outcome());
    }

    @Test
    void unclassifiedToolsAreAllowedAndCountedEvenWithTheGateOn() {
        when(registry.resolve(TOOL))
                .thenReturn(
                        new ToolClassificationDecision.Blocked(
                                TOOL, ToolClassificationDecision.Reason.UNCLASSIFIED, "no row"));

        assertEquals(
                ToolDispatchGate.Outcome.ALLOW, governance(true).decide(registry, TOOL).outcome());
        assertEquals(1.0, meters.counter(AgentToolGovernance.UNCLASSIFIED_ALLOWED_METRIC).count());
    }

    @Test
    void irreversibleWriteIsGatedToo() {
        when(registry.resolve(TOOL))
                .thenReturn(
                        new ToolClassificationDecision.Classified(
                                new ToolClassification(
                                        TOOL,
                                        ToolTrustTier.TENANT_SCOPED,
                                        ToolSensitivity.WRITE_TENANT,
                                        ToolDeterminism.DURABLE,
                                        null,
                                        true,
                                        true)));

        assertEquals(
                ToolDispatchGate.Outcome.REQUIRE_APPROVAL,
                governance(true).decide(registry, TOOL).outcome());
    }

    @Test
    void ordinaryClassifiedToolsAreAllowed() {
        when(registry.resolve(TOOL))
                .thenReturn(
                        new ToolClassificationDecision.Classified(
                                new ToolClassification(
                                        TOOL,
                                        ToolTrustTier.TENANT_SCOPED,
                                        ToolSensitivity.READ_TENANT,
                                        ToolDeterminism.PURE,
                                        null,
                                        false,
                                        true)));

        assertEquals(
                ToolDispatchGate.Outcome.ALLOW, governance(true).decide(registry, TOOL).outcome());
    }

    @Test
    void enforcementIsOnlyReportedOnceAToolGuardHoldsTheBean() {
        AgentToolGovernance governance = governance(false);
        assertFalse(governance.isEnforcing());

        governance.markWiredIntoDispatch();

        assertTrue(governance.isEnforcing());
    }

    @Test
    void outputRedactionIsScopedToTheCapturedTenant() {
        LlmGatewayToolOutputRedactor redactor = mock(LlmGatewayToolOutputRedactor.class);
        when(redactor.redact("tenant-a", "call me at a@b.com"))
                .thenReturn("call me at [REDACTED_EMAIL]");
        AgentToolGovernance governance =
                new AgentToolGovernance(
                        providerOf(registry),
                        providerOf((MeterRegistry) meters),
                        providerOf(redactor),
                        false,
                        "");

        UnaryOperator<String> scoped =
                governance.outputRedactorFor(TenantContext.of("tenant-a", "sub", Set.of()));

        assertEquals("call me at [REDACTED_EMAIL]", scoped.apply("call me at a@b.com"));
        assertNull(governance.outputRedactorFor(null), "no tenant means no policy to apply");
    }

    @Test
    void anAnnotationDerivedReadOnlyClaimNeverMakesAnMcpToolReplaySafe() {
        com.agenticai.core.agents.tools.classification.McpAnnotationClassifier classifier =
                new com.agenticai.core.agents.tools.classification.McpAnnotationClassifier(true);
        io.modelcontextprotocol.spec.McpSchema.ToolAnnotations readOnly =
                new io.modelcontextprotocol.spec.McpSchema.ToolAnnotations(
                        null, true, null, null, null, null);
        McpAnnotationCapture.capture(
                classifier,
                "t1",
                "crm",
                java.util.List.of(
                        new io.modelcontextprotocol.spec.McpSchema.Tool(
                                "update_record",
                                null,
                                "a tool",
                                java.util.Map.of("type", "object"),
                                null,
                                readOnly,
                                null)));
        ToolClassificationRegistry real =
                new ToolClassificationRegistry(
                        mock(
                                com.agenticai.core.agents.tools.classification
                                        .ToolClassificationRepository.class),
                        meters);
        real.setMcpAnnotations(classifier);
        AgentToolGovernance governance =
                new AgentToolGovernance(
                        providerOf(real),
                        providerOf((MeterRegistry) meters),
                        providerOf(null),
                        false,
                        "");

        TenantContext.set(TenantContext.of("t1", "u", Set.of()));
        try {
            assertTrue(
                    real.resolve("mcp__crm__update_record").isClassified(),
                    "the hint still classifies the tool for the dispatch gate and the trifecta detector");
            assertTrue(
                    governance.replaySafeVerdict("mcp__crm__update_record").isEmpty(),
                    "but the replay path treats it as unclassified (fail safe)");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void unclassifiedToolsRequireApprovalWhenConfiguredFailClosed() {
        when(registry.resolve(TOOL)).thenReturn(unclassified());

        ToolDispatchGate.Verdict verdict =
                governanceWithUnclassified("require-approval").decide(registry, TOOL);

        assertEquals(ToolDispatchGate.Outcome.REQUIRE_APPROVAL, verdict.outcome());
        assertEquals(
                1.0,
                meters.counter(
                                AgentToolGovernance.REFUSED_METRIC,
                                "reason",
                                "UNCLASSIFIED_APPROVAL")
                        .count());
    }

    @Test
    void unclassifiedToolsAreDeniedWhenConfiguredDeny() {
        when(registry.resolve(TOOL)).thenReturn(unclassified());

        ToolDispatchGate.Verdict verdict =
                governanceWithUnclassified("deny").decide(registry, TOOL);

        assertEquals(ToolDispatchGate.Outcome.DENY, verdict.outcome());
        assertTrue(verdict.reason().contains("no safety classification"), verdict.reason());
    }

    @Test
    void anUnknownUnclassifiedActionFallsBackToAllow() {
        when(registry.resolve(TOOL)).thenReturn(unclassified());

        assertEquals(
                ToolDispatchGate.Outcome.ALLOW,
                governanceWithUnclassified("bogus").decide(registry, TOOL).outcome());
    }

    @Test
    void theSetUnclassifiedActionDoesNotTouchClassifiedOrQuarantinedTools() {
        when(registry.resolve("quarantined_tool"))
                .thenReturn(
                        new ToolClassificationDecision.Blocked(
                                "quarantined_tool",
                                ToolClassificationDecision.Reason.QUARANTINED,
                                "manifest changed"));

        assertEquals(
                ToolDispatchGate.Outcome.DENY,
                governanceWithUnclassified("require-approval")
                        .decide(registry, "quarantined_tool")
                        .outcome());
    }

    private static ToolClassificationDecision unclassified() {
        return new ToolClassificationDecision.Blocked(
                TOOL, ToolClassificationDecision.Reason.UNCLASSIFIED, "no row");
    }

    private AgentToolGovernance governanceWithUnclassified(String action) {
        return new AgentToolGovernance(
                providerOf(registry),
                providerOf((MeterRegistry) meters),
                providerOf(null),
                false,
                "channel_send_message",
                action);
    }

    private AgentToolGovernance governance(boolean confirmationGate) {
        return new AgentToolGovernance(
                providerOf(registry),
                providerOf((MeterRegistry) meters),
                providerOf(null),
                confirmationGate,
                "channel_send_message");
    }

    private static ToolClassificationDecision irreversibleEgress() {
        return new ToolClassificationDecision.Classified(
                new ToolClassification(
                        TOOL,
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.EGRESS,
                        ToolDeterminism.DURABLE,
                        null,
                        true,
                        true));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
