package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationDecision;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.agents.tools.classification.ToolDeterminism;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.classification.ToolTrustTier;
import com.agenticai.core.agents.tools.localshell.LocalShellToolCallback;
import com.agenticai.core.guardrails.ApprovalGateMode;
import com.agenticai.core.guardrails.ToolDispatchGate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code app.assistant.local-shell.approval-required}: the install-level dial for the sandboxed
 * terminal. Default {@code true} keeps today's behaviour (local_shell is pinned in {@code
 * ALWAYS_CONFIRM_TOOLS} and parked by the classification gate); {@code false} lifts BOTH pins so a
 * command runs inside the sandbox without a per-call approval - while the other always-confirm
 * tools and the SUPERVISED tier stay exactly as strict.
 */
class LocalShellApprovalRequiredTest {

    private static final String SHELL = LocalShellToolCallback.RUN_NAME;

    private final ToolClassificationRegistry registry = mock(ToolClassificationRegistry.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private AgentToolGovernance governance(boolean approvalRequired) {
        return governance(approvalRequired, true);
    }

    private AgentToolGovernance governance(boolean approvalRequired, boolean desktopRequired) {
        return new AgentToolGovernance(
                providerOf(registry),
                providerOf((MeterRegistry) meters),
                providerOf(null),
                true,
                "channel_send_message",
                "allow",
                approvalRequired,
                desktopRequired);
    }

    private static ToolClassificationDecision irreversible(String tool) {
        return new ToolClassificationDecision.Classified(
                new ToolClassification(
                        tool,
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        true,
                        true));
    }

    @Test
    void defaultTrueKeepsLocalShellPinned() {
        when(registry.resolve(SHELL)).thenReturn(irreversible(SHELL));

        assertEquals(
                ToolDispatchGate.Outcome.REQUIRE_APPROVAL,
                governance(true).decide(registry, SHELL).outcome());
        assertEquals(true, governance(true).alwaysConfirm(SHELL));
    }

    @Test
    void flagOffLiftsBothPinsForLocalShellOnly() {
        when(registry.resolve(SHELL)).thenReturn(irreversible(SHELL));
        when(registry.resolve("a2a_call_agent")).thenReturn(irreversible("a2a_call_agent"));
        AgentToolGovernance governance = governance(false);

        // The classification gate lets the sandboxed terminal through...
        assertEquals(
                ToolDispatchGate.Outcome.ALLOW, governance.decide(registry, SHELL).outcome());
        // ...and the always-confirm pin is lifted for it alone.
        assertEquals(false, governance.alwaysConfirm(SHELL));
        // a2a_call_agent and other pins are untouched.
        assertEquals(true, governance.alwaysConfirm("a2a_call_agent"));
        assertEquals(
                ToolDispatchGate.Outcome.REQUIRE_APPROVAL,
                governance.decide(registry, "a2a_call_agent").outcome());
    }

    @Test
    void guardNeverGatesLocalShellForFullAutonomyWhenFlagOff() {
        AgentToolGuard guard =
                new AgentToolGuard(
                        null, null, null, null, null, 10, null, null, null, null, null,
                        governance(false));
        assertEquals(
                ApprovalGateMode.NEVER_GATE,
                guard.approvalGateModeFor(AgentAutonomyTier.FULLY_AUTONOMOUS, SHELL));
    }

    @Test
    void guardStillPinsLocalShellWhenFlagOn() {
        AgentToolGuard guard =
                new AgentToolGuard(
                        null, null, null, null, null, 10, null, null, null, null, null,
                        governance(true));
        assertEquals(
                ApprovalGateMode.POLICY_DEFAULT,
                guard.approvalGateModeFor(AgentAutonomyTier.FULLY_AUTONOMOUS, SHELL));
    }

    @Test
    void supervisedStaysGatedWhateverTheFlag() {
        AgentToolGuard guard =
                new AgentToolGuard(
                        null, null, null, null, null, 10, null, null, null, null, null,
                        governance(false));
        assertEquals(
                ApprovalGateMode.ALWAYS_GATE,
                guard.approvalGateModeFor(AgentAutonomyTier.SUPERVISED, SHELL));
    }

    @Test
    void desktopToolsArePinnedByDefaultAndFreedByTheirOwnDial() {
        when(registry.resolve("local_desktop_click")).thenReturn(irreversible("local_desktop_click"));
        when(registry.resolve("local_desktop_screen")).thenReturn(irreversible("local_desktop_screen"));

        // Default: actuating desktop tools are pinned like local_shell - even FULLY_AUTONOMOUS asks.
        AgentToolGovernance strict = governance(false, true);
        assertEquals(true, strict.alwaysConfirm("local_desktop_click"));
        assertEquals(
                ToolDispatchGate.Outcome.REQUIRE_APPROVAL,
                strict.decide(registry, "local_desktop_click").outcome());
        // The read side is never pinned - it is not on GATED_NAMES.
        assertEquals(false, strict.alwaysConfirm("local_desktop_screen"));

        // Flag off: actuating tools become ordinary exempt tools; the pin is gone.
        AgentToolGovernance free = governance(false, false);
        assertEquals(false, free.alwaysConfirm("local_desktop_click"));
        assertEquals(
                ToolDispatchGate.Outcome.ALLOW,
                free.decide(registry, "local_desktop_click").outcome());

        AgentToolGuard guard =
                new AgentToolGuard(
                        null, null, null, null, null, 10, null, null, null, null, null,
                        strict);
        assertEquals(
                ApprovalGateMode.POLICY_DEFAULT,
                guard.approvalGateModeFor(AgentAutonomyTier.FULLY_AUTONOMOUS, "local_desktop_click"));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
