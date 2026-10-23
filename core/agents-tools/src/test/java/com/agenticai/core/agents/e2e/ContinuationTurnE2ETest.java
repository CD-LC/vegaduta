package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.turn.ContinuationProperties;
import com.agenticai.core.providers.ProviderName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * When a turn is re-prompted because the model "stopped at an announced step", run through the real
 * turn pipeline (real {@code AgentChatService}, ChatClient, Spring AI tool loop, the production tool
 * wrapper chain); only the LLM provider is scripted.
 *
 * <p>What these pin: an ordinary closing line such as "Let me know if you need anything else" was
 * treated as an unfinished task and cost another model call; and when a pass really was continued, the
 * model was shown none of the tool work it had just done (the pass start was read after the pass had
 * finished), only the text it wrote.
 */
class ContinuationTurnE2ETest {

    private ApprovalTurnHarness harness;

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    private static void everyModel(ContinuationProperties p) {
        p.setHeuristicScope(ContinuationProperties.Scope.ALL_MODELS);
    }

    private static RecordingTool writeFile() {
        return RecordingTool.returning("write_file", "Writes a file into the project", "wrote pom.xml");
    }

    private static RecordingTool runIt() {
        return RecordingTool.returning("run_it", "Runs the project", "build ok");
    }

    @Test
    void aFinalAnswerThatOffersFurtherHelpIsNotRePrompted() {
        harness =
                ApprovalTurnHarness.builder()
                        .continuationPolicy(ContinuationTurnE2ETest::everyModel)
                        .tool(writeFile())
                        .responder(
                                request ->
                                        request.hasToolResult()
                                                ? ScriptedLlm.text("I created the file. Let me know if you need anything else.")
                                                : ScriptedLlm.callTool("c1", "write_file", "{\"to\":\"pom.xml\"}"))
                        .build();

        String reply = harness.chat("Create a pom.xml for the project");

        assertAll(
                () -> assertEquals(2, harness.llm().modelCalls(), "one tool round and the answer; no extra pass"),
                () -> assertTrue(reply.contains("Let me know if you need anything else")),
                () -> assertEquals(1, harness.tool("write_file").executions()));
    }

    @Test
    void aReplyThatStopsAtAnAnnouncedStepIsContinuedFromTheRealTranscript() {
        harness =
                ApprovalTurnHarness.builder()
                        .continuationPolicy(ContinuationTurnE2ETest::everyModel)
                        .tool(writeFile())
                        .tool(runIt())
                        .responder(
                                request -> {
                                    boolean nudged = request.userTexts().stream().anyMatch(t -> t.contains("stopped before"));
                                    if (nudged && request.toolResults().size() == 1) {
                                        return ScriptedLlm.callTool("c2", "run_it", "{\"to\":\"now\"}");
                                    }
                                    if (nudged) {
                                        return ScriptedLlm.text("Ran it, the build is ok. All done.");
                                    }
                                    if (request.hasToolResult()) {
                                        return ScriptedLlm.text("The file is written. Now let me run it:");
                                    }
                                    return ScriptedLlm.callTool("c1", "write_file", "{\"to\":\"pom.xml\"}");
                                })
                        .build();

        String reply = harness.chat("Create a pom.xml and build it");

        ScriptedLlm.Request continuation = harness.llm().requests().get(2);
        boolean sawToolUse =
                continuation.messages().stream()
                        .anyMatch(m -> m instanceof AssistantMessage a && a.hasToolCalls()
                                && a.getToolCalls().get(0).id().equals("c1"));
        boolean sawToolResult =
                continuation.messages().stream()
                        .anyMatch(m -> m instanceof ToolResponseMessage t && t.getResponses().get(0).id().equals("c1")
                                && t.getResponses().get(0).responseData().contains("wrote pom.xml"));
        assertAll(
                () -> assertTrue(reply.contains("All done"), reply),
                () -> assertEquals(4, harness.llm().modelCalls(), harness.llm().lastRequest().conversation()),
                () -> assertTrue(sawToolUse, "the continuation pass is sent the model's own tool-use message: " + continuation.conversation()),
                () -> assertTrue(sawToolResult, "and the tool's result as a tool-result message: " + continuation.conversation()),
                () -> assertEquals(1, harness.tool("write_file").executions(), "the first tool is not run again"),
                () -> assertEquals(1, harness.tool("run_it").executions()));
    }

    @Test
    void aContinuationPassThatMakesNoToolCallEndsTheLoop() {
        harness =
                ApprovalTurnHarness.builder()
                        .continuationPolicy(ContinuationTurnE2ETest::everyModel)
                        .tool(writeFile())
                        .responder(request -> ScriptedLlm.text(request.number() == 1 ? "Let me create the file:" : "Let me try again:"))
                        .build();

        harness.chat("Create a pom.xml");

        assertEquals(2, harness.llm().modelCalls(), "no progress after the first nudge, so no second one");
        assertFalse(harness.llm().requests().isEmpty());
    }

    @Test
    void aFrontierModelsTextOnlyReplyIsAnAnswerEvenWhenItReadsLikeAnAnnouncement() {
        harness =
                ApprovalTurnHarness.builder()
                        .tool(writeFile())
                        .responder(request -> ScriptedLlm.text("Let me create the file:"))
                        .build();

        String reply = harness.chat("Create a pom.xml");

        assertEquals("Let me create the file:", reply);
        assertEquals(1, harness.llm().modelCalls(), "the wording check does not apply to a model that is not a weak tool caller");
    }

    @Test
    void aProviderListedAsAWeakToolCallerIsContinued() {
        harness =
                ApprovalTurnHarness.builder()
                        .continuationPolicy(p -> p.setHeuristicProviders(java.util.Set.of(ProviderName.OPENAI)))
                        .tool(writeFile())
                        .responder(
                                request ->
                                        request.number() == 1
                                                ? ScriptedLlm.text("Let me create the file:")
                                                : request.number() == 2
                                                        ? ScriptedLlm.callTool("c1", "write_file", "{\"to\":\"pom.xml\"}")
                                                        : ScriptedLlm.text("The file is created. All done."))
                        .build();

        String reply = harness.chat("Create a pom.xml");

        assertTrue(reply.endsWith("All done."), reply);
        assertEquals(1, harness.tool("write_file").executions());
    }

    @Test
    void theBufferedDepthsUseTheSamePolicy() {
        harness =
                ApprovalTurnHarness.builder()
                        .depth(ReasoningDepth.CAREFUL)
                        .continuationPolicy(ContinuationTurnE2ETest::everyModel)
                        .tool(writeFile())
                        .tool(runIt())
                        .responder(
                                request -> {
                                    boolean nudged = request.userTexts().stream().anyMatch(t -> t.contains("stopped before"));
                                    if (nudged && request.toolResults().size() == 1) {
                                        return ScriptedLlm.callTool("c2", "run_it", "{\"to\":\"now\"}");
                                    }
                                    if (nudged) {
                                        return ScriptedLlm.text("Ran it, the build is ok. All done.");
                                    }
                                    if (request.hasToolResult()) {
                                        return ScriptedLlm.text("The file is written. Now let me run it:");
                                    }
                                    return ScriptedLlm.callTool("c1", "write_file", "{\"to\":\"pom.xml\"}");
                                })
                        .build();

        String reply = harness.chat("Create a pom.xml and build it");

        ScriptedLlm.Request continuation = harness.llm().requests().get(2);
        assertAll(
                () -> assertTrue(reply.contains("All done"), reply),
                () -> assertTrue(reply.contains("Now let me run it:"), "the pieces are joined: " + reply),
                () -> assertTrue(continuation.toolResults().stream().anyMatch(r -> r.contains("wrote pom.xml")),
                        "the model is sent its tool result as a tool result: " + continuation.conversation()),
                () -> assertEquals(1, harness.tool("write_file").executions()),
                () -> assertEquals(1, harness.tool("run_it").executions()));
    }

    @Test
    void aBufferedClosingLineIsNotRePrompted() {
        harness =
                ApprovalTurnHarness.builder()
                        .depth(ReasoningDepth.CAREFUL)
                        .continuationPolicy(ContinuationTurnE2ETest::everyModel)
                        .tool(writeFile())
                        .responder(
                                request ->
                                        request.hasToolResult()
                                                ? ScriptedLlm.text("Created. Let me know if you need anything else.")
                                                : ScriptedLlm.callTool("c1", "write_file", "{\"to\":\"pom.xml\"}"))
                        .build();

        harness.chat("Create a pom.xml");

        assertEquals(2, harness.llm().modelCalls());
    }
}
