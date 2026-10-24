package com.agenticai.core.agents.job.approval;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.e2e.ScriptedLlm;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalStatus;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The other outcomes of the approval-continuation contract for a background job, through the real
 * job execution, approval sweep, turn pipeline and approval stack (see {@link
 * AgentJobApprovalE2ERig}): a long result reaches the resumed turn whole, a denial either ends the
 * job (the default) or is delivered to the resumed turn, and one decision is delivered once.
 */
class AgentJobApprovalContinuationVariantsE2ETest {

    private static final String GOAL = AgentJobApprovalE2ERig.GOAL;
    private static final String TOOL = AgentJobApprovalE2ERig.TOOL;

    private AgentJobApprovalE2ERig rig;

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

    private boolean settled() throws InterruptedException {
        boolean resumedAndIdle =
                AgentJobApprovalTestSupport.awaitTrue(
                        () -> rig.job.getTurnsUsed() >= 2 && rig.executor.activeCount() == 0, 20_000L);
        AgentJobApprovalTestSupport.awaitTrue(
                () -> rig.job.getStatus() != AgentJobStatus.RUNNING, 5_000L);
        return resumedAndIdle;
    }

    @Test
    void aLongResultReachesTheResumedJobWholeNotAsTheFiveHundredCharacterNote() throws Exception {
        String tail = "TAIL-CONF-9042";
        String output = "BEGIN " + "lorem ipsum ".repeat(300) + tail;
        rig =
                AgentJobApprovalE2ERig.build(
                        output, callsTheToolThenFinishesOn(tail, "Done: " + tail), false, p -> {});
        rig.runUntilParked();
        assertEquals(AgentJobStatus.AWAITING_APPROVAL, rig.job.getStatus());
        int before = rig.harness.llm().modelCalls();

        rig.harness.approvePending();

        assertTrue(settled(), "the job never resumed");
        ScriptedLlm.Request first = rig.harness.llm().requests().get(before);
        assertAll(
                () -> assertTrue(output.length() > 3_000),
                () -> assertTrue(first.mentions(tail), "the resumed turn lacked the end of the result"),
                () -> assertEquals(AgentJobStatus.SUCCEEDED, rig.job.getStatus()),
                () -> assertEquals(1, rig.tool.executions()));
    }

    @Test
    void theResumedJobsMessageRestatesTheJobsOwnGoalAndOnlyThatTurnGetsIt() throws Exception {
        rig =
                AgentJobApprovalE2ERig.build(
                        "sent CONF-1", callsTheToolThenFinishesOn("CONF-1", "Done CONF-1"), false, p -> {});
        rig.runUntilParked();
        int before = rig.harness.llm().modelCalls();
        rig.harness.approvePending();
        assertTrue(settled());

        ScriptedLlm.Request first = rig.harness.llm().requests().get(before);
        // The first request of the FIRST run was the bare goal; the resumed run's is the continuation.
        String firstRunMessage = rig.harness.llm().requests().get(0).userTexts().get(0);
        assertAll(
                () -> assertEquals(GOAL, firstRunMessage),
                () ->
                        assertTrue(
                                first.userTexts().stream()
                                        .anyMatch(t -> t.startsWith("<approval_result") && t.contains(GOAL)),
                                first.userTexts().toString()));
    }

    @Test
    void byDefaultADeniedApprovalStillEndsTheJobAsRejected() throws Exception {
        rig =
                AgentJobApprovalE2ERig.build(
                        "sent", callsTheToolThenFinishesOn("DENIED", "Adapted."), false, p -> {});
        rig.runUntilParked();
        assertEquals(AgentJobStatus.AWAITING_APPROVAL, rig.job.getStatus());
        int modelCallsWhenParked = rig.harness.llm().modelCalls();

        ApprovalRequest denied = rig.harness.rejectPending("Not now");
        rig.sweepAfterDecision(denied);

        assertAll(
                () -> assertEquals(AgentJobStatus.FAILED, rig.job.getStatus()),
                () -> assertEquals("APPROVAL_REJECTED", rig.job.getErrorCode()),
                () -> assertEquals(0, rig.tool.executions()),
                () -> assertEquals(modelCallsWhenParked, rig.harness.llm().modelCalls(), "no resumed turn"));
    }

    @Test
    void withTheSwitchOnADenialIsDeliveredToTheResumedJobSoItCanAdapt() throws Exception {
        rig =
                AgentJobApprovalE2ERig.build(
                        "sent",
                        callsTheToolThenFinishesOn("DENIED", "Understood; I left the invoice unsent."),
                        true,
                        p -> {});
        rig.runUntilParked();
        int before = rig.harness.llm().modelCalls();

        ApprovalRequest denied = rig.harness.rejectPending("Not before the audit");
        rig.sweepAfterDecision(denied);

        assertTrue(settled(), "the job never resumed after the denial");
        ScriptedLlm.Request first = rig.harness.llm().requests().get(before);
        assertAll(
                () -> assertEquals(ApprovalStatus.REJECTED, denied.getStatus()),
                () -> assertEquals(0, rig.tool.executions(), "a denied call never runs"),
                () -> assertTrue(first.mentions("Not before the audit"), first.conversation()),
                () -> assertTrue(first.mentions("DENIED")),
                () -> assertEquals(AgentJobStatus.SUCCEEDED, rig.job.getStatus()),
                () -> assertTrue(rig.job.getResultText().contains("left the invoice unsent")),
                () -> assertEquals(1, rig.harness.approvalRequests().size()));
    }

    @Test
    void aDecisionIsDeliveredToTheJobOnce() throws Exception {
        rig =
                AgentJobApprovalE2ERig.build(
                        "sent CONF-1", callsTheToolThenFinishesOn("CONF-1", "Done CONF-1"), false, p -> {});
        rig.runUntilParked();
        rig.harness.approvePending();
        assertTrue(settled());
        AgentJobApprovalResume resume =
                new AgentJobApprovalResume(
                        // the same request store the rig's sweep reads
                        lookupOf(rig),
                        rig.harness.continuationFactory(),
                        rig.harness.continuationPrompt(),
                        rig.harness.continuationClaims(),
                        rig.harness.continuationProperties());

        Optional<com.agenticai.core.agents.job.AgentJobApprovalResumption.ResumeTurn> again = resume.resumeFor(rig.job, GOAL);

        assertFalse(again.isPresent(), "the approved result was already delivered to the resumed turn");
    }

    private static AgentJobApprovalLookup lookupOf(AgentJobApprovalE2ERig rig) {
        AgentJobApprovalLookup lookup = org.mockito.Mockito.mock(AgentJobApprovalLookup.class);
        org.mockito.Mockito.when(lookup.findTop20ByRequestedByOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> rig.harness.approvalRequests());
        return lookup;
    }
}
