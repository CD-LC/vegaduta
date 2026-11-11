package com.agenticai.core.guardrails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.prompts.PromptScope;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tenant and agent overrides of the guardrail texts reach the guarded tool callback: refusals,
 * denied/fail-closed reasons, withheld output, the step ceiling and the approval park texts.
 */
class GuardrailPromptRegistryToolCallTest extends GuardrailPromptRegistryFixture {

    // ------------------------------------------------------------------ overrides reach the real
    // paths

    @Test
    void aTenantOverrideOfTheRefusalReachesTheModelAndAnAgentOverrideBeatsItForThatAgentOnly() {
        tenant();
        ToolDispatchGate deny = name -> ToolDispatchGate.Verdict.deny("it is quarantined.");
        GuardedToolCallback a =
                guarded(
                        AGENT,
                        deny,
                        passThrough(),
                        mock(ApprovalPolicyService.class),
                        null,
                        null,
                        false,
                        null);

        assertThat(a.call("{}"))
                .isEqualTo(
                        shipped(
                                GuardrailPrompts.DISPATCH_DENIED,
                                "tool",
                                "send_invoice",
                                "reason",
                                "it is quarantined."));

        override(
                PromptScope.TENANT,
                null,
                GuardrailPrompts.DISPATCH_DENIED,
                "Tenant: {tool} is off ({reason}).");
        assertThat(a.call("{}")).isEqualTo("Tenant: send_invoice is off (it is quarantined.).");

        override(PromptScope.AGENT, AGENT, GuardrailPrompts.DISPATCH_DENIED, "Agent: no {tool}.");
        assertThat(a.call("{}")).isEqualTo("Agent: no send_invoice.");
        GuardedToolCallback other =
                guarded(
                        OTHER_AGENT,
                        deny,
                        passThrough(),
                        mock(ApprovalPolicyService.class),
                        null,
                        null,
                        false,
                        null);
        assertThat(other.call("{}")).isEqualTo("Tenant: send_invoice is off (it is quarantined.).");
    }

    @Test
    void theDefaultReasonOfADeniedToolAndTheFailClosedReasonResolveThroughTheRegistry() {
        tenant();
        override(
                PromptScope.TENANT,
                null,
                GuardrailPrompts.DISPATCH_DENIED_DEFAULT_REASON,
                "blocked by the house.");
        override(
                PromptScope.TENANT,
                null,
                GuardrailPrompts.DISPATCH_CHECK_FAILED,
                "policy offline.");
        ToolDispatchGate noReason = name -> ToolDispatchGate.Verdict.deny(null);
        ToolDispatchGate broken =
                name -> {
                    throw new IllegalStateException("policy store down");
                };

        assertThat(
                        guarded(
                                        AGENT,
                                        noReason,
                                        passThrough(),
                                        mock(ApprovalPolicyService.class),
                                        null,
                                        null,
                                        false,
                                        null)
                                .call("{}"))
                .contains("blocked by the house.");
        assertThat(
                        guarded(
                                        AGENT,
                                        broken,
                                        passThrough(),
                                        mock(ApprovalPolicyService.class),
                                        null,
                                        null,
                                        false,
                                        null)
                                .call("{}"))
                .contains("policy offline.");
    }

    @Test
    void theWithheldOutputAndTenantRuleTextsResolveThroughTheRegistry() {
        tenant();
        GuardrailService blocking = mock(GuardrailService.class);
        when(blocking.sanitizeToolOutput(anyString(), anyString(), any()))
                .thenThrow(new GuardrailViolationException("send_invoice", "pattern hit"));
        GuardedToolCallback callback =
                guarded(
                        AGENT,
                        null,
                        blocking,
                        mock(ApprovalPolicyService.class),
                        null,
                        null,
                        false,
                        null);

        assertThat(callback.call("{}"))
                .isEqualTo(
                        shipped(
                                GuardrailPrompts.OUTPUT_WITHHELD,
                                "tool",
                                "send_invoice",
                                "reason",
                                "Guardrail blocked output from tool 'send_invoice': pattern hit"));

        override(
                PromptScope.TENANT,
                null,
                GuardrailPrompts.OUTPUT_WITHHELD,
                "Withheld {tool}: {reason}");
        assertThat(callback.call("{}"))
                .isEqualTo(
                        "Withheld send_invoice: Guardrail blocked output from tool 'send_invoice': pattern hit");
    }

    @Test
    void theStepCeilingResultFollowsTheRegistryAndTheOldPropertyAsAnAlias() {
        tenant();
        GuardedToolCallback callback =
                new GuardedToolCallback(
                                tool("send_invoice"),
                                new AtomicInteger(5),
                                3,
                                passThrough(),
                                mock(ApprovalPolicyService.class),
                                null,
                                AGENT,
                                "s1",
                                null,
                                false,
                                null,
                                null,
                                ApprovalGateMode.POLICY_DEFAULT,
                                null,
                                null,
                                null,
                                null,
                                null)
                        .withPrompts(resolver);
        // calls 6.. are past a ceiling of 3, within the default grace
        assertThat(callback.call("{}")).isEqualTo(new ToolStepLimitProperties().exhaustedResult(3));

        ToolStepLimitProperties edited = new ToolStepLimitProperties();
        edited.setExhaustedMessage("Out of budget after {limit}.");
        callback.withStepLimits(edited);
        assertThat(callback.call("{}"))
                .isEqualTo(edited.exhaustedResult(3))
                .contains("Out of budget after 3.");

        override(
                PromptScope.TENANT,
                null,
                GuardrailPrompts.TOOL_BUDGET_CALLS,
                "Registry wins at {limit}.");
        assertThat(callback.call("{}"))
                .contains("Registry wins at 3.")
                .doesNotContain("Out of budget");
        assertThat(ToolBudgetExhausted.isBudgetExhausted(callback.call("{}"))).isTrue();
    }

    @Test
    void theParkTextFollowsTheRegistryTheOperatorPropertyAndTheShippedDefaultInThatOrder() {
        tenant();
        ApprovalPolicyService policy = mock(ApprovalPolicyService.class);
        when(policy.requiresApproval(anyString())).thenReturn(true);

        // 1. nothing customised: the shipped text, filled in, wrapped in the approval_parked marker
        GuardedToolCallback shippedWording =
                guarded(AGENT, null, passThrough(), policy, approvalGate(), null, false, null);
        assertThat(shippedWording.call("{}"))
                .isEqualTo(
                        ApprovalParked.result(
                                shipped(
                                        GuardrailPrompts.PARK_AWAITING,
                                        "tool",
                                        "send_invoice",
                                        "requestId",
                                        "00000000-0000-0000-0000-00000000beef")));

        // 2. the old property, edited by an operator, still works
        ApprovalParkProperties edited = new ApprovalParkProperties();
        edited.setAwaitingMessage("Hold on: {tool} waits for {requestId}.");
        GuardedToolCallback viaProperty =
                guarded(AGENT, null, passThrough(), policy, approvalGate(), null, false, null)
                        .withApprovalParkTexts(edited);
        assertThat(viaProperty.call("{}"))
                .isEqualTo(
                        ApprovalParked.result(
                                "Hold on: send_invoice waits for 00000000-0000-0000-0000-00000000beef."));

        // 3. a registry override (tenant) beats the property
        override(
                PromptScope.TENANT,
                null,
                GuardrailPrompts.PARK_AWAITING,
                "Registry: {tool} / {requestId}.");
        GuardedToolCallback viaRegistry =
                guarded(AGENT, null, passThrough(), policy, approvalGate(), null, false, null)
                        .withApprovalParkTexts(edited);
        assertThat(viaRegistry.call("{}"))
                .isEqualTo(
                        ApprovalParked.result(
                                "Registry: send_invoice / 00000000-0000-0000-0000-00000000beef."));

        // 4. a call made after the park is answered with the queued text of the same registry
        override(
                PromptScope.AGENT,
                AGENT,
                GuardrailPrompts.PARK_QUEUED,
                "Queued {tool} behind {parkedTool} as {requestId}.");
        assertThat(viaRegistry.call("{\"again\":1}"))
                .isEqualTo(
                        ApprovalParked.result(
                                "Queued send_invoice behind send_invoice as 00000000-0000-0000-0000-00000000beef."));
    }

    @Test
    void aRecordedFailureOfTheApprovalRequestUsesTheRecordFailedKey() {
        tenant();
        ApprovalPolicyService policy = mock(ApprovalPolicyService.class);
        when(policy.requiresApproval(anyString())).thenReturn(true);
        HumanApprovalGateService failing = mock(HumanApprovalGateService.class);
        when(failing.recordToolCallForApproval(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString(),
                        any(),
                        any()))
                .thenThrow(new IllegalStateException("db down"));
        override(
                PromptScope.AGENT,
                AGENT,
                GuardrailPrompts.PARK_RECORD_FAILED,
                "Could not submit {tool}.");

        assertThat(
                        guarded(AGENT, null, passThrough(), policy, failing, null, false, null)
                                .call("{}"))
                .isEqualTo("Could not submit send_invoice.");
    }
}
