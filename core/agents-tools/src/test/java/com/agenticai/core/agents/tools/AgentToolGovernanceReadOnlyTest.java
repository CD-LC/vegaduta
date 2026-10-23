package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.tools.classification.McpAnnotationClassifier;
import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationDecision;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.agents.tools.classification.ToolClassificationRepository;
import com.agenticai.core.agents.tools.classification.ToolDeterminism;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.classification.ToolTrustTier;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.guardrails.ApprovalGateMode;
import com.agenticai.core.guardrails.GuardrailSettings;
import com.agenticai.core.guardrails.GuardrailTuningProperties;
import com.agenticai.core.guardrails.ReadOnlyAutoAllow;
import com.agenticai.core.guardrails.ToolDispatchGate;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Which calls a SUPERVISED agent may make unattended ({@code app.guardrails.supervised.gate-read-only=false}): the per-call
 * read-only verdict of the classification dispatch gate, and the per-agent decision to use it at all.
 */
class AgentToolGovernanceReadOnlyTest {

    private static final UUID AGENT = UUID.randomUUID();

    private final ToolClassificationRegistry registry = mock(ToolClassificationRegistry.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final TrifectaEnforcementService trifecta = mock(TrifectaEnforcementService.class);

    @AfterEach
    void clear() {
        GuardrailSettings.clear();
        TenantContext.clear();
    }

    private AgentToolGovernance governance() {
        return new AgentToolGovernance(providerOf(registry), providerOf((MeterRegistry) meters), providerOf(null), true, "");
    }

    private static ToolClassificationDecision classified(String name, ToolSensitivity sensitivity, boolean irreversible) {
        return new ToolClassificationDecision.Classified(new ToolClassification(
                name, ToolTrustTier.TENANT_SCOPED, sensitivity, ToolDeterminism.SIDE_EFFECT, null, irreversible, true));
    }

    private static ToolClassificationDecision unclassified(String name) {
        return new ToolClassificationDecision.Blocked(name, ToolClassificationDecision.Reason.UNCLASSIFIED, null);
    }

    private void classify(String name, ToolClassificationDecision decision) {
        when(registry.resolve(name)).thenReturn(decision);
        when(registry.resolveIgnoringMcpAnnotations(name)).thenReturn(decision);
    }

    private ToolDispatchGate.Verdict verdict(String tool, boolean markReadOnly) {
        return governance().decide(registry, tool, AGENT, markReadOnly);
    }

    // ------------------------------------------------------------------------------ the per-call verdict

    @Test
    void anExplicitReadOnlyClassificationIsVouchedForOnlyWhenAsked() {
        classify("web_search", classified("web_search", ToolSensitivity.READ_PUBLIC, false));
        classify("list_things", classified("list_things", ToolSensitivity.READ_TENANT, false));

        assertTrue(verdict("web_search", true).readOnly());
        assertTrue(verdict("list_things", true).readOnly());
        assertEquals(ToolDispatchGate.Outcome.ALLOW, verdict("web_search", true).outcome());
        assertFalse(verdict("web_search", false).readOnly(), "the plain gate never marks a call read-only");
    }

    @Test
    void aWriteACredentialedOrEgressToolIsNotReadOnly() {
        classify("remember_fact", classified("remember_fact", ToolSensitivity.WRITE_TENANT, false));
        classify("use_secret", classified("use_secret", ToolSensitivity.CREDENTIALED, false));
        classify("send_note", classified("send_note", ToolSensitivity.EGRESS, false));

        assertFalse(verdict("remember_fact", true).readOnly());
        assertFalse(verdict("use_secret", true).readOnly());
        assertFalse(verdict("send_note", true).readOnly());
    }

    @Test
    void anIrreversibleToolIsNeverReadOnlyWhateverItsSensitivity() {
        classify("odd_read", classified("odd_read", ToolSensitivity.READ_TENANT, true));

        ToolDispatchGate.Verdict v = verdict("odd_read", true);

        assertEquals(ToolDispatchGate.Outcome.ALLOW, v.outcome());
        assertFalse(v.readOnly());
    }

    @Test
    void anUnclassifiedQuarantinedOrInvalidToolIsNeverReadOnly() {
        classify("mystery", unclassified("mystery"));
        classify("held", new ToolClassificationDecision.Blocked(
                "held", ToolClassificationDecision.Reason.QUARANTINED, null));

        assertFalse(verdict("mystery", true).readOnly(), "the default 'allow' for an unclassified tool is not a read-only verdict");
        assertEquals(ToolDispatchGate.Outcome.DENY, verdict("held", true).outcome());
        assertFalse(verdict("held", true).readOnly());
    }

    @Test
    void aToolPinnedToAlwaysConfirmIsNeverReadOnly() {
        // Even if a row (wrongly) said it was a read, the pin keeps a human in front of it.
        classify("a2a_call_agent", classified("a2a_call_agent", ToolSensitivity.READ_PUBLIC, false));

        assertFalse(verdict("a2a_call_agent", true).readOnly());
    }

    @Test
    void aReadOnlyClaimFromAnMcpServersOwnAnnotationsIsNotAnExplicitClassification() {
        McpAnnotationClassifier classifier = new McpAnnotationClassifier(true);
        io.modelcontextprotocol.spec.McpSchema.ToolAnnotations readOnlyHint =
                new io.modelcontextprotocol.spec.McpSchema.ToolAnnotations(null, true, null, null, null, null);
        McpAnnotationCapture.capture(classifier, "t1", "crm", java.util.List.of(
                new io.modelcontextprotocol.spec.McpSchema.Tool(
                        "update_record", null, "a tool", null, null, readOnlyHint, null)));
        ToolClassificationRegistry real = new ToolClassificationRegistry(mock(ToolClassificationRepository.class), meters);
        real.setMcpAnnotations(classifier);
        AgentToolGovernance governance = new AgentToolGovernance(
                providerOf(real), providerOf((MeterRegistry) meters), providerOf(null), true, "");
        TenantContext.set(TenantContext.of("t1", "u", Set.of()));

        assertTrue(real.resolve("mcp__crm__update_record").isClassified(), "the hint classifies the tool for other uses");
        ToolDispatchGate.Verdict v = governance.decide(real, "mcp__crm__update_record", AGENT, true);

        assertEquals(ToolDispatchGate.Outcome.ALLOW, v.outcome());
        assertFalse(v.readOnly(), "a server's own 'read-only' claim must not let a possibly mutating tool run unattended");
    }

    // ------------------------------------------------------------------------------ the per-agent decision

    private AgentToolGovernance wired(boolean gateReadOnly, Boolean notLethal) {
        GuardrailTuningProperties tuning = new GuardrailTuningProperties();
        tuning.getSupervised().setGateReadOnly(gateReadOnly);
        AgentToolGovernance governance = governance();
        governance.setGuardrailTuning(tuning);
        governance.setTrifectaEnforcement(trifecta);
        when(trifecta.definitelyNotLethal(AGENT)).thenReturn(Boolean.TRUE.equals(notLethal));
        return governance;
    }

    @Test
    void offAndNotATrifectaAutoAllowsReadOnlyCalls() {
        assertTrue(wired(false, true).supervisedAutoAllowsReadOnly(AGENT));
    }

    @Test
    void theBaseValueGates() {
        assertFalse(wired(true, true).supervisedAutoAllowsReadOnly(AGENT));
    }

    @Test
    void aTrifectaOrAnUnjudgedAgentKeepsFullGating() {
        assertFalse(wired(false, false).supervisedAutoAllowsReadOnly(AGENT));
    }

    @Test
    void noTrifectaServiceNoClassificationRegistryOrNoAgentKeepsTheGate() {
        GuardrailTuningProperties off = new GuardrailTuningProperties();
        off.getSupervised().setGateReadOnly(false);

        AgentToolGovernance noTrifecta = governance();
        noTrifecta.setGuardrailTuning(off);
        assertFalse(noTrifecta.supervisedAutoAllowsReadOnly(AGENT), "cannot judge the agent");

        AgentToolGovernance noRegistry = new AgentToolGovernance(
                providerOf(null), providerOf((MeterRegistry) meters), providerOf(null), true, "");
        noRegistry.setGuardrailTuning(off);
        noRegistry.setTrifectaEnforcement(trifecta);
        when(trifecta.definitelyNotLethal(AGENT)).thenReturn(true);
        assertFalse(noRegistry.supervisedAutoAllowsReadOnly(AGENT), "no registry: nothing can be vouched for");

        assertFalse(wired(false, true).supervisedAutoAllowsReadOnly(null));
    }

    @Test
    void noTuningBeanMeansTheShippedDefault() {
        AgentToolGovernance governance = governance();
        governance.setTrifectaEnforcement(trifecta);
        when(trifecta.definitelyNotLethal(AGENT)).thenReturn(true);

        assertFalse(governance.supervisedAutoAllowsReadOnly(AGENT));
    }

    // ------------------------------------------------------------------------------ the guard's tier mapping

    private AgentToolGuard guardOver(AgentToolGovernance governance) {
        return new AgentToolGuard(null, null, null, null, null, 10, null, null, null, null, null, governance);
    }

    @Test
    void supervisedBecomesGateUnlessReadOnlyOnlyWhenTheGovernanceAllowsIt() {
        assertEquals(ApprovalGateMode.GATE_UNLESS_READ_ONLY,
                guardOver(wired(false, true)).approvalGateModeFor(AgentAutonomyTier.SUPERVISED, "web_search", AGENT));
        assertEquals(ApprovalGateMode.ALWAYS_GATE,
                guardOver(wired(true, true)).approvalGateModeFor(AgentAutonomyTier.SUPERVISED, "web_search", AGENT));
        assertEquals(ApprovalGateMode.ALWAYS_GATE,
                guardOver(wired(false, false)).approvalGateModeFor(AgentAutonomyTier.SUPERVISED, "web_search", AGENT));
        assertEquals(ApprovalGateMode.ALWAYS_GATE,
                guardOver(null).approvalGateModeFor(AgentAutonomyTier.SUPERVISED, "web_search", AGENT));
        assertEquals(ApprovalGateMode.ALWAYS_GATE,
                guardOver(wired(false, true)).approvalGateModeFor(AgentAutonomyTier.SUPERVISED, "web_search"),
                "no agent id: the old two-argument form keeps the gate");
    }

    @Test
    void theOtherTiersAndThePlumbingToolsAreUnchanged() {
        AgentToolGuard guard = guardOver(wired(false, true));

        assertEquals(ApprovalGateMode.POLICY_DEFAULT,
                guard.approvalGateModeFor(AgentAutonomyTier.SEMI_AUTONOMOUS, "web_search", AGENT));
        assertEquals(ApprovalGateMode.NEVER_GATE,
                guard.approvalGateModeFor(AgentAutonomyTier.FULLY_AUTONOMOUS, "web_search", AGENT));
        assertEquals(ApprovalGateMode.POLICY_DEFAULT,
                guard.approvalGateModeFor(AgentAutonomyTier.FULLY_AUTONOMOUS, "a2a_call_agent", AGENT));
    }

    // ------------------------------------------------------------------------------ the audit sink

    @Test
    void anAutoAllowedCallIsWrittenToTheAuditLogWithTheNote() {
        AuditLogService audit = mock(AuditLogService.class);
        AgentToolGovernance governance = governance();
        governance.setAuditLog(providerOf(audit));

        governance.readOnlyAutoAllowAudit().autoAllowed("web_search", AGENT, "sess-1", ReadOnlyAutoAllow.NOTE);

        verify(audit).record(
                eq("agent-supervised"), eq("tool.supervised.auto_allowed"), eq("tool"), eq("web_search"),
                eq(AuditOutcome.AUTO_APPROVED), contains("auto-allowed: read-only under SUPERVISED"));
    }

    @Test
    void anAuditLogThatFailsOrIsAbsentNeverFailsTheCall() {
        AuditLogService audit = mock(AuditLogService.class);
        when(audit.record(anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyString()))
                .thenThrow(new IllegalStateException("audit store down"));
        AgentToolGovernance failing = governance();
        failing.setAuditLog(providerOf(audit));
        failing.readOnlyAutoAllowAudit().autoAllowed("web_search", AGENT, "sess-1", ReadOnlyAutoAllow.NOTE);

        governance().readOnlyAutoAllowAudit().autoAllowed("web_search", AGENT, "sess-1", ReadOnlyAutoAllow.NOTE);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
