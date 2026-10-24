package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.agents.tools.classification.ToolClassificationRepository;
import com.agenticai.core.agents.turn.replay.EvalToolEffectPolicy;
import com.agenticai.core.agents.turn.replay.ReplayTurnRunner;
import com.agenticai.core.agents.turn.replay.ReplayTurnRunner.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * An eval replay with tools, through the REAL turn pipeline (real guard chain, real approval service, real tool-call log,
 * real tool classification table of the first-party tools) with only the LLM scripted, answering the questions that make
 * the full pipeline safe to run as a test:
 *
 * <ul>
 *   <li>a tool that only reads runs for real, in the same turn in which a tool that writes, sends or pays is answered with a
 *       simulated result and never reached;
 *   <li>no kind of effect leaks: every mutating or outbound class of first-party tool is simulated, ungated or approved;
 *   <li>an approval is auto-resolved only when the caller asked for the sandbox auto-approval, and an approved call that
 *       could change something is still simulated;
 *   <li>a held call records no approval request, so nothing reaches an approver;
 *   <li>a tool nobody classified is simulated unless the operator vouched for it, and the vouching cannot reach a declared
 *       write.
 * </ul>
 */
class EvalSandboxTurnE2ETest {

    private static final String ACTOR = "eval-auto-approve";

    private ApprovalTurnHarness harness;

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    /** The runner over the real classification table (the compiled-in declarations; no stored rows). */
    static ReplayTurnRunner runner(ApprovalTurnHarness harness) {
        ToolClassificationRepository repository = mock(ToolClassificationRepository.class);
        when(repository.findByToolName(anyString())).thenReturn(Optional.empty());
        when(repository.findPatternRows()).thenReturn(List.of());
        ToolClassificationRegistry registry = new ToolClassificationRegistry(repository, new SimpleMeterRegistry());
        EvalToolEffectPolicy policy = new EvalToolEffectPolicy(HarnessProviders.provider(registry));
        return new ReplayTurnRunner(
                harness.chatService(), harness.toolCallLogService(), HarnessProviders.provider(policy));
    }

    private ReplayTurnRunner.Request request(String actor, List<String> vouched) {
        return new ReplayTurnRunner.Request(
                harness.agentId(), "eval:run-1:0", harness.subject(), "Do the task.", true, actor, List.of(), vouched,
                Duration.ofSeconds(60));
    }

    /** A model that calls each named tool once, in order, then answers. */
    private static ScriptedLlm.Responder callsInOrder(String... tools) {
        return request -> {
            int done = request.toolResults().size();
            return done < tools.length
                    ? ScriptedLlm.callTool("c" + done, tools[done], "{\"to\":\"billing@acme.test\"}")
                    : ScriptedLlm.text("All done.");
        };
    }

    private static RecordingTool tool(String name) {
        return RecordingTool.returning(name, "The " + name + " tool", "real result of " + name);
    }

    // ------------------------------------------------------------------ effects

    @Test
    void aReaderRunsForRealWhileAWriteInTheSameTurnIsSimulatedAndTheTurnCarriesOn() {
        RecordingTool search = tool("search_knowledge_base");
        RecordingTool remember = tool("remember_fact");
        harness = ApprovalTurnHarness.builder()
                .tool(search)
                .tool(remember)
                .responder(callsInOrder("search_knowledge_base", "remember_fact"))
                .build();

        ReplayTurnRunner.Result result = runner(harness).run(request(null, List.of()));

        assertAll(
                () -> assertEquals(1, search.executions(), "the reader really ran"),
                () -> assertEquals(0, remember.executions(), "the write never reached the tool"),
                () -> assertEquals(2, result.toolCalls().size()),
                () -> assertEquals(Status.OK, result.toolCalls().get(0).status()),
                () -> assertEquals(Status.SIMULATED, result.toolCalls().get(1).status()),
                () -> assertEquals(List.of("remember_fact"), result.simulatedTools()),
                () -> assertEquals("All done.", result.replyText(), "the agent carried on after the simulated success"),
                () -> assertTrue(harness.llm().requests().get(2).mentions("was simulated because this conversation is an evaluation"),
                        "the model was told what happened: " + harness.llm().requests().get(2).conversation()),
                () -> assertTrue(harness.approvalRequests().isEmpty()));
    }

    @Test
    void noClassOfEffectLeaksAnUngatedToolOfEachIsSimulated() {
        List<String> effects = List.of(
                "google_gmail", "channel_send_message", "http_request_write", "data_table_write", "add_to_knowledge_base",
                "workspace_write", "run_code", "local_shell", "coding_task", "schedule_task", "create_payment_link",
                "browser_click", "upload_video_to_youtube", "delegate_to_specialist", "generate_document");
        for (String name : effects) {
            RecordingTool effect = tool(name);
            try (ApprovalTurnHarness h = ApprovalTurnHarness.builder()
                    .tool(effect)
                    .responder(callsInOrder(name))
                    .build()) {
                harness = h;
                ReplayTurnRunner.Result result = runner(h).run(request(null, List.of()));
                assertEquals(0, effect.executions(), name + " must never run in an eval");
                // The tenant's default approval policy already gates some of these (a payment, a delete): then the
                // call is held, as in production. Either way the tool is not reached and the report says so.
                Status status = result.toolCalls().get(0).status();
                assertTrue(status == Status.SIMULATED || status == Status.AWAITING_APPROVAL, name + " was " + status);
                assertEquals(1, result.simulatedTools().size() + result.heldTools().size(), name);
                assertTrue(h.approvalRequests().isEmpty(), name + " must not notify an approver");
            }
            harness = null;
        }
    }

    // ------------------------------------------------------------------ approvals

    @Test
    void aGatedReaderWithTheAutoApprovalRunsOnceCountsItAndRecordsNoRequest() {
        RecordingTool search = tool("search_knowledge_base");
        harness = ApprovalTurnHarness.builder()
                .tool(search)
                .gateTool("search_knowledge_base")
                .responder(callsInOrder("search_knowledge_base"))
                .build();

        ReplayTurnRunner.Result result = runner(harness).run(request(ACTOR, List.of()));

        assertAll(
                () -> assertEquals(1, search.executions()),
                () -> assertEquals(1, result.autoApprovedCalls()),
                () -> assertEquals(Status.OK, result.toolCalls().get(0).status()),
                () -> assertTrue(result.heldTools().isEmpty()),
                () -> assertTrue(harness.approvalRequests().isEmpty()));
    }

    @Test
    void aGatedReaderWithoutTheAutoApprovalIsHeldAndNothingIsRecordedForAnApprover() {
        RecordingTool search = tool("search_knowledge_base");
        harness = ApprovalTurnHarness.builder()
                .tool(search)
                .gateTool("search_knowledge_base")
                .responder(callsInOrder("search_knowledge_base"))
                .build();

        ReplayTurnRunner.Result result = runner(harness).run(request(null, List.of()));

        assertAll(
                () -> assertEquals(0, search.executions(), "approvals auto-resolve only through the sandbox auto-approval"),
                () -> assertEquals(0, result.autoApprovedCalls()),
                () -> assertEquals(List.of("search_knowledge_base"), result.heldTools()),
                () -> assertEquals(Status.AWAITING_APPROVAL, result.toolCalls().get(0).status()),
                () -> assertTrue(harness.approvalRequests().isEmpty(), "no request, so no notification to an approver"));
    }

    @Test
    void aGatedWriteTheSandboxApprovedIsStillSimulatedNotRun() {
        RecordingTool remember = tool("remember_fact");
        harness = ApprovalTurnHarness.builder()
                .tool(remember)
                .gateTool("remember_fact")
                .responder(callsInOrder("remember_fact"))
                .build();

        ReplayTurnRunner.Result result = runner(harness).run(request(ACTOR, List.of()));

        assertAll(
                () -> assertEquals(0, remember.executions()),
                () -> assertEquals(1, result.autoApprovedCalls(), "approved ..."),
                () -> assertEquals(List.of("remember_fact"), result.simulatedTools(), "... and simulated"),
                () -> assertEquals(Status.SIMULATED, result.toolCalls().get(0).status()),
                () -> assertTrue(harness.approvalRequests().isEmpty()));
    }

    // ------------------------------------------------------------------ what the arguments change

    @Test
    void httpRequestRunsForAGetAndIsSimulatedForAPost() {
        RecordingTool get = tool("http_request");
        harness = ApprovalTurnHarness.builder()
                .tool(get)
                .responder(r -> r.toolResults().isEmpty()
                        ? ScriptedLlm.callTool("c0", "http_request", "{\"method\":\"GET\",\"url\":\"https://x.test/a\"}")
                        : ScriptedLlm.text("ok"))
                .build();
        ReplayTurnRunner.Result read = runner(harness).run(request(null, List.of()));
        assertEquals(1, get.executions(), "a GET reads");
        assertEquals(Status.OK, read.toolCalls().get(0).status());
        harness.close();

        RecordingTool post = tool("http_request");
        harness = ApprovalTurnHarness.builder()
                .tool(post)
                .responder(r -> r.toolResults().isEmpty()
                        ? ScriptedLlm.callTool("c0", "http_request", "{\"method\":\"POST\",\"url\":\"https://x.test/a\"}")
                        : ScriptedLlm.text("ok"))
                .build();
        ReplayTurnRunner.Result write = runner(harness).run(request(null, List.of()));
        assertEquals(0, post.executions(), "a POST changes something at the far end");
        assertEquals(Status.SIMULATED, write.toolCalls().get(0).status());
    }

    // ------------------------------------------------------------------ tools nobody classified

    @Test
    void aToolNobodyClassifiedIsSimulatedUnlessTheOperatorVouchedForItAndVouchingNeverReachesADeclaredWrite() {
        RecordingTool lookup = tool("crm_lookup_contact");
        RecordingTool delete = tool("crm_delete_contact");
        RecordingTool gmail = tool("google_gmail");
        harness = ApprovalTurnHarness.builder()
                .tool(lookup)
                .tool(delete)
                .tool(gmail)
                .responder(callsInOrder("crm_lookup_contact", "crm_delete_contact", "google_gmail"))
                .build();

        ReplayTurnRunner.Result result = runner(harness).run(request(null, List.of("crm_lookup_*", "google_gmail")));

        assertAll(
                () -> assertEquals(1, lookup.executions(), "vouched for, and unclassified"),
                () -> assertEquals(0, delete.executions(), "not vouched for"),
                () -> assertEquals(0, gmail.executions(), "declared EGRESS: the operator's list cannot make it run"),
                () -> assertEquals(List.of("crm_delete_contact", "google_gmail"), result.simulatedTools()));
    }

    // ------------------------------------------------------------------ quick mode

    @Test
    void aQuickReplayOffersNoToolsAtAll() {
        RecordingTool search = tool("search_knowledge_base");
        harness = ApprovalTurnHarness.builder()
                .tool(search)
                .responder(r -> ScriptedLlm.text("I would look it up."))
                .build();

        ReplayTurnRunner.Result result = runner(harness).run(new ReplayTurnRunner.Request(
                harness.agentId(), "eval:run-1:0", harness.subject(), "Do the task.", false, null, List.of(),
                Duration.ofSeconds(60)));

        assertAll(
                () -> assertEquals("I would look it up.", result.replyText()),
                () -> assertTrue(result.toolCalls().isEmpty()),
                () -> assertTrue(harness.llm().requests().get(0).offeredTools().isEmpty()),
                () -> assertEquals(0, search.executions()),
                () -> assertFalse(harness.llm().requests().isEmpty()));
    }
}
