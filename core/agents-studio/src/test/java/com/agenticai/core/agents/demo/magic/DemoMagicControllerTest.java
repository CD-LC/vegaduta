package com.agenticai.core.agents.demo.magic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.demo.magic.DemoMagicController.MagicPlanRequest;
import com.agenticai.core.agents.demo.magic.DemoMagicController.MagicRunRequest;
import com.agenticai.core.agents.demo.magic.DemoMagicService.RunAdmission;
import com.agenticai.core.guardrails.moderation.ModerationContext;
import com.agenticai.core.guardrails.moderation.ModerationOutcome;
import com.agenticai.core.guardrails.moderation.ModerationService;
import com.agenticai.core.tenancy.demo.DemoMagicWorkflow;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionRepository;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Status codes and error keys of the Magic Studio owner and public halves. */
class DemoMagicControllerTest {

    private static final UUID WF_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID SESSION_ID = UUID.fromString("66666666-7777-8888-9999-000000000000");

    private DemoMagicService magic;
    private DemoSessionService sessions;
    private DemoSessionRepository repository;
    private ModerationService moderation;
    private DemoMagicController controller;
    private DemoSession session;
    private DemoMagicWorkflow workflow;
    private final MockHttpServletRequest http = new MockHttpServletRequest();

    @SuppressWarnings({"unchecked", "rawtypes"})
    @BeforeEach
    void setUp() {
        magic = mock(DemoMagicService.class);
        sessions = mock(DemoSessionService.class);
        repository = mock(DemoSessionRepository.class);
        moderation = mock(ModerationService.class);
        ObjectProvider<ModerationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(moderation);
        controller = new DemoMagicController(magic, sessions, repository, provider);

        session = mock(DemoSession.class);
        when(session.getId()).thenReturn(SESSION_ID);
        when(session.getTenantId()).thenReturn(UUID.randomUUID());
        when(session.getAgentId()).thenReturn(UUID.randomUUID());
        workflow = mock(DemoMagicWorkflow.class);
        when(workflow.getId()).thenReturn(WF_ID);
        when(workflow.getSessionId()).thenReturn(SESSION_ID);
        when(workflow.getIntent()).thenReturn("WRITE");
        when(workflow.getRunCap()).thenReturn(3);
        when(workflow.getRunsUsed()).thenReturn(1);
        when(workflow.getTitle()).thenReturn("Caption maker");

        when(magic.isEnabled()).thenReturn(true);
        when(sessions.resolve("tok")).thenReturn(Optional.of(session));
        when(repository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(magic.resolveByShareId("share")).thenReturn(Optional.of(workflow));
        when(magic.findOwned(session, WF_ID)).thenReturn(Optional.of(workflow));
        when(magic.admitRunner(any(), anyString())).thenReturn(RunAdmission.ALLOWED);
        when(magic.execute(any(), any(), anyString(), any(), any()))
                .thenReturn(new DemoMagicService.RunResult("text", "done", null, false));
        when(sessions.inDemoTenantContext(any(), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier) inv.getArgument(1)).get());
        ModerationOutcome allowed = mock(ModerationOutcome.class);
        when(allowed.isAllowed()).thenReturn(true);
        when(moderation.moderate(anyString(), any(ModerationContext.class))).thenReturn(allowed);
        http.setRemoteAddr("203.0.113.9");
    }

    private static void assertStatus(ResponseEntity<?> response, int status, String error) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        if (error != null) {
            assertThat(response.getBody()).isEqualTo(Map.of("error", error));
        }
    }

    private static MagicRunRequest run(String prompt) {
        return new MagicRunRequest(Map.of("prompt", prompt));
    }

    // ---- plan ----

    @Test
    void planIsRefusedWhenDisabledUnknownExpiredOrBlank() {
        when(magic.isEnabled()).thenReturn(false);
        assertStatus(controller.plan("tok", new MagicPlanRequest("p", null)), 403, "disabled");

        when(magic.isEnabled()).thenReturn(true);
        assertStatus(controller.plan("nope", new MagicPlanRequest("p", null)), 404, "invalid");

        when(session.isExpired(any(Instant.class))).thenReturn(true);
        assertStatus(controller.plan("tok", new MagicPlanRequest("p", null)), 410, "expired");

        when(session.isExpired(any(Instant.class))).thenReturn(false);
        assertStatus(controller.plan("tok", null), 422, "invalid");
        assertStatus(controller.plan("tok", new MagicPlanRequest("   ", null)), 422, "invalid");
        assertStatus(
                controller.plan("tok", new MagicPlanRequest("x".repeat(2001), null)),
                422,
                "invalid");
    }

    @Test
    void planReturnsTheWorkflowIdAndPlanOrTheWorkflowCap() {
        DemoMagicPlanner.MagicPlan plan = mock(DemoMagicPlanner.MagicPlan.class);
        when(magic.planAndPersist(eq(session), eq("a poster"), eq("https://x.example")))
                .thenReturn(Optional.of(new DemoMagicService.PlannedWorkflow(workflow, plan)));

        ResponseEntity<?> ok =
                controller.plan("tok", new MagicPlanRequest("  a poster ", "https://x.example"));

        assertStatus(ok, 200, null);
        assertThat(((DemoMagicController.MagicPlanResponse) ok.getBody()).workflowId())
                .isEqualTo(WF_ID);

        when(magic.planAndPersist(any(), anyString(), any())).thenReturn(Optional.empty());
        assertStatus(
                controller.plan("tok", new MagicPlanRequest("more", null)), 429, "workflow_cap");
    }

    // ---- preview / publish ----

    @Test
    void previewNeedsAKnownSessionAndAnOwnedWorkflow() {
        assertStatus(controller.preview("nope", WF_ID, run("hi"), http), 404, "invalid");
        when(magic.findOwned(session, WF_ID)).thenReturn(Optional.empty());
        assertStatus(controller.preview("tok", WF_ID, run("hi"), http), 404, "invalid");
    }

    @Test
    void anOwnerPreviewRunsWithoutTouchingThePublicRunCaps() {
        ResponseEntity<?> response = controller.preview("tok", WF_ID, run(" hi "), http);

        assertStatus(response, 200, null);
        verify(magic).execute(session, workflow, "hi", null, "203.0.113.9");
        verify(magic, never()).admitRunner(any(), anyString());
        verify(sessions).recordMessage(session);
    }

    @Test
    void publishReturnsTheShareLinkAndCaps() {
        when(magic.publish(session, WF_ID))
                .thenReturn(
                        Optional.of(new DemoMagicService.PublishedLink("abc", "https://s/abc")));
        when(magic.runCap()).thenReturn(3);
        when(magic.perIpRuns()).thenReturn(1);

        ResponseEntity<?> response = controller.publish("tok", WF_ID);

        assertStatus(response, 200, null);
        assertThat(response.getBody())
                .isEqualTo(
                        new DemoMagicController.MagicPublishResponse("abc", "https://s/abc", 3, 1));
    }

    @Test
    void publishIsRefusedWhenDisabledUnknownOrNotOwned() {
        when(magic.publish(session, WF_ID)).thenReturn(Optional.empty());
        assertStatus(controller.publish("tok", WF_ID), 404, "invalid");
        assertStatus(controller.publish("nope", WF_ID), 404, "invalid");
        when(magic.isEnabled()).thenReturn(false);
        assertStatus(controller.publish("tok", WF_ID), 403, "disabled");
    }

    // ---- public run page ----

    @Test
    void theRunMetaIsJustTheTitleFormAndRemainingRuns() {
        DemoMagicPlanner.MagicPlan plan = mock(DemoMagicPlanner.MagicPlan.class);
        List<DemoMagicPlanner.FormField> form =
                List.of(new DemoMagicPlanner.FormField("prompt", "Ask", "textarea", true, "..."));
        when(plan.formSpec()).thenReturn(form);
        when(magic.readPlan(workflow)).thenReturn(plan);
        Instant expires = Instant.now().plusSeconds(3600);
        when(workflow.getExpiresAt()).thenReturn(expires);

        ResponseEntity<?> response = controller.runMeta("share");

        assertStatus(response, 200, null);
        assertThat(response.getBody())
                .isEqualTo(
                        new DemoMagicController.MagicRunMetaResponse(
                                "Caption maker", form, expires, 2));
    }

    @Test
    void theRunMetaNeverShowsANegativeRemainingCount() {
        DemoMagicPlanner.MagicPlan plan = mock(DemoMagicPlanner.MagicPlan.class);
        when(magic.readPlan(workflow)).thenReturn(plan);
        when(workflow.getRunsUsed()).thenReturn(9);

        ResponseEntity<?> response = controller.runMeta("share");

        assertThat(((DemoMagicController.MagicRunMetaResponse) response.getBody()).runsRemaining())
                .isZero();
    }

    @Test
    void theRunMetaIsRefusedForDisabledUnknownRevokedOrExpired() {
        when(magic.isEnabled()).thenReturn(false);
        assertStatus(controller.runMeta("share"), 403, "disabled");
        when(magic.isEnabled()).thenReturn(true);
        assertStatus(controller.runMeta("other"), 404, "invalid");
        when(workflow.isRevoked()).thenReturn(true);
        assertStatus(controller.runMeta("share"), 410, "expired");
        when(workflow.isRevoked()).thenReturn(false);
        when(workflow.isExpired(any(Instant.class))).thenReturn(true);
        assertStatus(controller.runMeta("share"), 410, "expired");
    }

    @Test
    void aPublicRunIsRefusedWhenItsWorkflowOrOwnerIsGone() {
        when(magic.isEnabled()).thenReturn(false);
        assertStatus(controller.run("share", run("hi"), http), 403, "disabled");
        when(magic.isEnabled()).thenReturn(true);
        assertStatus(controller.run("other", run("hi"), http), 404, "invalid");
        when(workflow.isRevoked()).thenReturn(true);
        assertStatus(controller.run("share", run("hi"), http), 410, "expired");
        when(workflow.isRevoked()).thenReturn(false);
        when(repository.findById(SESSION_ID)).thenReturn(Optional.empty());
        assertStatus(controller.run("share", run("hi"), http), 410, "expired");
        when(repository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(session.isExpired(any(Instant.class))).thenReturn(true);
        assertStatus(controller.run("share", run("hi"), http), 410, "expired");
        verify(magic, never()).admitRunner(any(), anyString());
    }

    @Test
    void everyAdmissionRefusalIsA429WithItsOwnKeyAndSpendsNothing() {
        when(magic.admitRunner(workflow, "203.0.113.9")).thenReturn(RunAdmission.ALREADY_RAN);
        assertStatus(controller.run("share", run("hi"), http), 429, "already_ran");
        when(magic.admitRunner(workflow, "203.0.113.9")).thenReturn(RunAdmission.RUNNER_CAP);
        assertStatus(controller.run("share", run("hi"), http), 429, "runner_cap");
        when(magic.admitRunner(workflow, "203.0.113.9")).thenReturn(RunAdmission.RUNS_EXHAUSTED);
        assertStatus(controller.run("share", run("hi"), http), 429, "runs_exhausted");
        verify(magic, never()).execute(any(), any(), anyString(), any(), any());
    }

    @Test
    void anAdmittedPublicRunExecutesAndChargesTheOwnersMessageAllowance() {
        ResponseEntity<?> response =
                controller.run(
                        "share",
                        new MagicRunRequest(Map.of("prompt", "hi", "link", " https://a.example ")),
                        http);

        assertStatus(response, 200, null);
        verify(magic).execute(session, workflow, "hi", "https://a.example", "203.0.113.9");
        verify(sessions).recordMessage(session);
    }

    @Test
    void invalidInputIsA422BeforeAnySpend() {
        assertStatus(controller.run("share", null, http), 422, "invalid");
        assertStatus(controller.run("share", new MagicRunRequest(null), http), 422, "invalid");
        assertStatus(controller.run("share", run("   "), http), 422, "invalid");
        assertStatus(controller.run("share", run("x".repeat(2001)), http), 422, "invalid");
        assertStatus(
                controller.run(
                        "share",
                        new MagicRunRequest(Map.of("prompt", "hi", "link", "l".repeat(2049))),
                        http),
                422,
                "invalid");
        verify(sessions, never()).recordMessage(any());
    }

    @Test
    void anExhaustedMessageAllowanceStopsATextRunButNotAnImageRun() {
        when(sessions.isMessageCapReached(session)).thenReturn(true);
        assertStatus(controller.run("share", run("hi"), http), 429, "budget_exhausted");
        verify(magic, never()).execute(any(), any(), anyString(), any(), any());

        when(workflow.getIntent()).thenReturn("IMAGE");
        assertStatus(controller.run("share", run("hi"), http), 200, null);
        verify(sessions, never()).recordMessage(any());
    }

    @Test
    void moderatedInputIsRejectedBeforeDispatch() {
        ModerationOutcome blocked = mock(ModerationOutcome.class);
        when(blocked.isAllowed()).thenReturn(false);
        when(moderation.moderate(anyString(), any(ModerationContext.class))).thenReturn(blocked);

        assertStatus(controller.run("share", run("bad words"), http), 422, "moderated");
        verify(magic, never()).execute(any(), any(), anyString(), any(), any());
        verify(sessions, never()).recordMessage(any());
    }

    @Test
    void moderationFailuresFailOpenAndAWorkflowAgentWinsOverTheSessionAgent() {
        UUID agent = UUID.randomUUID();
        when(workflow.getAgentId()).thenReturn(agent);
        when(moderation.moderate(anyString(), any(ModerationContext.class)))
                .thenThrow(new IllegalStateException("moderation down"));

        assertStatus(controller.run("share", run("hi"), http), 200, null);
        verify(magic).execute(any(), any(), eq("hi"), any(), any());
    }

    @Test
    void withoutAnAgentOrModerationServiceNothingIsModerated() {
        when(session.getAgentId()).thenReturn(null);
        assertStatus(controller.run("share", run("hi"), http), 200, null);
        verify(moderation, never()).moderate(anyString(), any(ModerationContext.class));
    }
}
