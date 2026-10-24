package com.agenticai.core.agents.job.approval;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.auxiliary.AuxiliaryModelProperties;
import com.agenticai.core.agents.e2e.ScriptedLlm;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.agents.memory.MemoryLimitsProperties;
import com.agenticai.core.agents.settings.BehaviourSettingsCatalog;
import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import com.agenticai.core.agents.turn.ChatCacheProperties;
import com.agenticai.core.agents.turn.ChatFinishProperties;
import com.agenticai.core.agents.turn.ContinuationProperties;
import com.agenticai.core.agents.turn.FollowUpProperties;
import com.agenticai.core.agents.turn.quality.RoutingSimpleProperties;
import com.agenticai.core.approval.ApprovalExpiryProperties;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.chat.LlmRetryProperties;
import com.agenticai.core.chat.ToolLoopProperties;
import com.agenticai.core.guardrails.GuardrailTuningProperties;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.DefaultSettingsResolver;
import com.agenticai.core.settings.InMemorySettingsStore;
import com.agenticai.core.settings.SettingOverride;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingsProperties;
import com.agenticai.core.settings.SettingsRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Whether a decided approval is carried into a parked job's resumed turn is a setting a workspace or an agent controls
 * ({@code approvals.continuation.*}). The same real pipeline as {@link AgentJobApprovalContinuationVariantsE2ETest}
 * (job execution, approval sweep, turn pipeline, approval stack), with the real catalog entries behind the real resolver:
 * an agent or workspace override changes what the job does, a settings failure falls back to the platform properties.
 */
class AgentJobApprovalSettingsE2ETest {

    private static final String TOOL = AgentJobApprovalE2ERig.TOOL;

    private AgentJobApprovalE2ERig rig;
    private final InMemorySettingsStore store = new InMemorySettingsStore();

    @AfterEach
    void tearDown() {
        if (rig != null) {
            rig.close();
        }
    }

    private static ScriptedLlm.Responder callsTheToolThenFinishesOn(String evidence, String finalText) {
        return request -> {
            if (request.mentions(evidence)) {
                return ScriptedLlm.text(finalText);
            }
            if (request.hasToolResult()) {
                return ScriptedLlm.text("Waiting for approval.");
            }
            return ScriptedLlm.callTool("call-" + request.number(), TOOL, "{\"to\":\"billing@acme.test\"}");
        };
    }

    private static DefaultSettingsResolver resolver(ApprovalContinuationProperties platform, InMemorySettingsStore backing) {
        BehaviourSettingsCatalog catalog = new BehaviourSettingsCatalog(
                platform, new ApprovalExpiryProperties(), new ChatFinishProperties(), new LlmRetryProperties(),
                new ChatCacheProperties(), new FollowUpProperties(), new ContinuationProperties(), new ToolLoopProperties(),
                new RoutingSimpleProperties(), new MemoryLimitsProperties(), new GuardrailTuningProperties(),
                new AuxiliaryModelProperties());
        SettingsProperties props = new SettingsProperties();
        props.setCacheTtl(Duration.ZERO);
        return new DefaultSettingsResolver(new SettingsRegistry(List.of(catalog)), backing, List.of(), props, new MockEnvironment());
    }

    /** Wires the settings catalog into the rig's real sweep and real resume builder. */
    private void useCatalog(DefaultSettingsResolver resolver) {
        rig.sweep.setSettingsResolver(resolver);
        AgentJobApprovalResume resume = new AgentJobApprovalResume(
                lookupOverTheRigsRequests(),
                rig.harness.continuationFactory(),
                rig.harness.continuationPrompt(),
                rig.harness.continuationClaims(),
                rig.harness.continuationProperties());
        resume.setSettingsResolver(resolver);
        rig.execution.setApprovalResume(resume);
    }

    private AgentJobApprovalLookup lookupOverTheRigsRequests() {
        AgentJobApprovalLookup lookup = mock(AgentJobApprovalLookup.class);
        when(lookup.findTop20ByRequestedByOrderByCreatedAtDesc(anyString())).thenAnswer(inv -> rig.harness.approvalRequests());
        return lookup;
    }

    private void tenantOverride(String key, boolean value) {
        write(new SettingOverride(SettingScope.TENANT, "", key, value, 1, "tester", Instant.now()));
    }

    private void agentOverride(String key, boolean value) {
        write(new SettingOverride(SettingScope.AGENT, rig.job.getAgentId().toString(), key, value, 1, "tester", Instant.now()));
    }

    private void write(SettingOverride row) {
        Optional<TenantContext> before = TenantContext.current();
        TenantContext.set(TenantContext.of(AgentJobApprovalE2ERig.TENANT, "tester", Set.of()));
        try {
            store.saveCurrent(row);
        } finally {
            before.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    private boolean settled() throws InterruptedException {
        boolean resumedAndIdle = AgentJobApprovalTestSupport.awaitTrue(
                () -> rig.job.getTurnsUsed() >= 2 && rig.executor.activeCount() == 0, 20_000L);
        AgentJobApprovalTestSupport.awaitTrue(() -> rig.job.getStatus() != AgentJobStatus.RUNNING, 5_000L);
        return resumedAndIdle;
    }

    @Test
    void aWorkspaceCanLetItsJobsCarryOnAfterADenialWhereThePlatformEndsThem() throws Exception {
        rig = AgentJobApprovalE2ERig.build(
                "sent", callsTheToolThenFinishesOn("DENIED", "Understood; I left the invoice unsent."), false, p -> {});
        useCatalog(resolver(rig.harness.continuationProperties(), store));
        tenantOverride("approvals.continuation.jobsContinueAfterDenial", true);
        rig.runUntilParked();
        int before = rig.harness.llm().modelCalls();

        ApprovalRequest denied = rig.harness.rejectPending("Not before the audit");
        rig.sweepAfterDecision(denied);

        assertTrue(settled(), "the job never resumed after the denial");
        ScriptedLlm.Request first = rig.harness.llm().requests().get(before);
        assertAll(
                () -> assertEquals(0, rig.tool.executions(), "a denied call never runs"),
                () -> assertTrue(first.mentions("Not before the audit"), first.conversation()),
                () -> assertEquals(AgentJobStatus.SUCCEEDED, rig.job.getStatus()));
    }

    @Test
    void anAgentCanEndItsJobsOnADenialWhereThePlatformLetsThemCarryOn() throws Exception {
        rig = AgentJobApprovalE2ERig.build(
                "sent", callsTheToolThenFinishesOn("DENIED", "Adapted."), true, p -> {});
        useCatalog(resolver(rig.harness.continuationProperties(), store));
        agentOverride("approvals.continuation.jobsContinueAfterDenial", false);
        rig.runUntilParked();
        int modelCallsWhenParked = rig.harness.llm().modelCalls();

        ApprovalRequest denied = rig.harness.rejectPending("Not now");
        rig.sweepAfterDecision(denied);

        assertAll(
                () -> assertEquals(AgentJobStatus.FAILED, rig.job.getStatus()),
                () -> assertEquals("APPROVAL_REJECTED", rig.job.getErrorCode()),
                () -> assertEquals(modelCallsWhenParked, rig.harness.llm().modelCalls(), "no resumed turn"));
    }

    @Test
    void anAgentThatTurnsContinuationOffResumesWithItsPlainGoalAndNotTheApprovalMessage() throws Exception {
        rig = AgentJobApprovalE2ERig.build(
                "sent CONF-1", callsTheToolThenFinishesOn("CONF-1", "Done CONF-1"), false, p -> {});
        useCatalog(resolver(rig.harness.continuationProperties(), store));
        agentOverride("approvals.continuation.enabled", false);
        rig.runUntilParked();
        int before = rig.harness.llm().modelCalls();

        rig.harness.approvePending();

        assertTrue(settled(), "the job never resumed");
        ScriptedLlm.Request first = rig.harness.llm().requests().get(before);
        assertFalse(
                first.userTexts().stream().anyMatch(t -> t.startsWith("<approval_result")),
                "continuation is off for this agent: " + first.userTexts());
    }

    @Test
    void aSettingsFailureLeavesThePlatformPropertiesInCharge() throws Exception {
        rig = AgentJobApprovalE2ERig.build(
                "sent", callsTheToolThenFinishesOn("DENIED", "Understood."), true, p -> {});
        InMemorySettingsStore broken = new InMemorySettingsStore() {
            @Override
            public Map<String, Object> tenantValues() {
                throw new IllegalStateException("settings table unavailable");
            }

            @Override
            public Map<String, Object> agentValues(String agentId) {
                throw new IllegalStateException("settings table unavailable");
            }
        };
        useCatalog(resolver(rig.harness.continuationProperties(), broken));
        rig.runUntilParked();
        int before = rig.harness.llm().modelCalls();

        ApprovalRequest denied = rig.harness.rejectPending("Not now");
        rig.sweepAfterDecision(denied);

        assertTrue(settled(), "the platform says carry on after a denial, and a broken settings store must not change that");
        assertEquals(AgentJobStatus.SUCCEEDED, rig.job.getStatus());
        assertTrue(rig.harness.llm().requests().get(before).mentions("Not now"));
    }

    @Test
    void anotherWorkspacesOverrideDoesNotReachThisJob() throws Exception {
        rig = AgentJobApprovalE2ERig.build("sent", callsTheToolThenFinishesOn("DENIED", "Adapted."), false, p -> {});
        useCatalog(resolver(rig.harness.continuationProperties(), store));
        Optional<TenantContext> before = TenantContext.current();
        TenantContext.set(TenantContext.of("t_other", "tester", Set.of()));
        try {
            store.saveCurrent(new SettingOverride(
                    SettingScope.TENANT, "", "approvals.continuation.jobsContinueAfterDenial", true, 1, "tester", Instant.now()));
        } finally {
            before.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
        rig.runUntilParked();

        ApprovalRequest denied = rig.harness.rejectPending("Not now");
        rig.sweepAfterDecision(denied);

        assertAll(
                () -> assertEquals(AgentJobStatus.FAILED, rig.job.getStatus()),
                () -> assertEquals("APPROVAL_REJECTED", rig.job.getErrorCode()));
    }
}
