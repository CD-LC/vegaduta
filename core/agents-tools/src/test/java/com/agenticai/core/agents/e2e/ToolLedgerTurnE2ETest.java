package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.turn.ToolLedger;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Session history keeps a compact tool ledger. The model on turn N+1 used to see only its own prose about turn
 * N: not the file id a tool returned, not which write calls it had already made, not that an action was
 * waiting on a human. Run through the real turn pipeline (real guard chain, real session memory over an
 * in-memory Redis, real prompt assembly); only the LLM is scripted.
 */
class ToolLedgerTurnE2ETest {

    private static final String REPORT = "Create the report file";
    private static final String ASK = "What was the id of that file?";
    private static final String FILE_RESULT = "{\"fileId\":\"file-42\",\"path\":\"/work/report.md\"}";

    private ApprovalTurnHarness harness;

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    private static RecordingTool createFile() {
        return RecordingTool.returning("create_file", "Creates a file in the workspace", FILE_RESULT);
    }

    /** Calls create_file for the first request, answers from whatever it was shown for the second. */
    private static ScriptedLlm.Responder fileTask() {
        return request -> {
            if (request.hasToolResult()) {
                return ScriptedLlm.text("I created the report.");
            }
            String last = request.userTexts().get(request.userTexts().size() - 1);
            if (last.equals(REPORT)) {
                return ScriptedLlm.callTool("c1", "create_file", "{\"to\":\"report.md\"}");
            }
            return request.mentions("file-42")
                    ? ScriptedLlm.text("The file id is file-42.")
                    : ScriptedLlm.text("I do not know which file you mean.");
        };
    }

    private List<Message> assistantMessagesOfTheLastRequest() {
        return harness.llm().lastRequest().messages().stream()
                .filter(m -> m.getMessageType() == MessageType.ASSISTANT)
                .toList();
    }

    @Test
    void aLaterTurnSeesWhatTheToolsDidAndCanAnswerFromIt() {
        harness = ApprovalTurnHarness.builder().tool(createFile()).responder(fileTask()).build();

        harness.chat(REPORT);
        String answer = harness.chat(ASK);

        List<Message> assistantTurns = assistantMessagesOfTheLastRequest();
        String firstTurnAsReplayed = assistantTurns.get(0).getText();
        assertAll(
                () -> assertEquals("The file id is file-42.", answer, "the model answered from the record"),
                () -> assertEquals(1, harness.tool("create_file").executions(), "and did not run the tool again"),
                () -> assertTrue(firstTurnAsReplayed.contains("create_file"), firstTurnAsReplayed),
                () -> assertTrue(firstTurnAsReplayed.contains("report.md"), "the arguments: " + firstTurnAsReplayed),
                () -> assertTrue(firstTurnAsReplayed.contains("file-42"), "the result: " + firstTurnAsReplayed),
                () -> assertTrue(firstTurnAsReplayed.contains("ok"), "the status: " + firstTurnAsReplayed),
                () -> assertTrue(firstTurnAsReplayed.endsWith("I created the report."), "the reply still follows: " + firstTurnAsReplayed));
    }

    @Test
    void theReplayedRecordSaysThePlatformWroteItAndTheModelMayRelyOnIt() {
        harness = ApprovalTurnHarness.builder().tool(createFile()).responder(fileTask()).build();

        harness.chat(REPORT);
        harness.chat(ASK);

        String system =
                harness.llm().lastRequest().messages().stream()
                        .filter(m -> m.getMessageType() == MessageType.SYSTEM)
                        .map(Message::getText)
                        .reduce("", (a, b) -> a + "\n" + b);
        String replayed = assistantMessagesOfTheLastRequest().get(0).getText();
        assertAll(
                () -> assertTrue(replayed.startsWith("[Platform tool record"), replayed),
                () -> assertTrue(replayed.contains("you may rely on them"), replayed),
                () -> assertTrue(system.contains("platform tool record") && system.contains("reliable evidence"),
                        "the system context says the record counts as tool use in this conversation: " + system));
    }

    @Test
    void theStoredTranscriptKeepsTheReplyAsTheModelSaidItAndTheRecordApart() {
        harness = ApprovalTurnHarness.builder().tool(createFile()).responder(fileTask()).build();

        harness.chat(REPORT);

        List<ChatTurn> stored = harness.storedTranscript();
        ChatTurn assistant = stored.get(stored.size() - 1);
        assertAll(
                () -> assertEquals(MessageRole.ASSISTANT, assistant.role()),
                () -> assertEquals("I created the report.", assistant.content(), "what a person reloads is unchanged"),
                () -> assertNotNull(assistant.tools()),
                () -> assertEquals(1, assistant.tools().entries().size()),
                () -> assertEquals("create_file", assistant.tools().entries().get(0).tool()),
                () -> assertEquals(ToolLedger.Status.OK, assistant.tools().entries().get(0).status()),
                () -> assertTrue(assistant.tools().entries().get(0).result().contains("file-42")),
                () -> assertNull(stored.get(0).tools(), "the user turn carries no record"));
    }

    @Test
    void aTurnWithoutToolCallsStoresNoRecordAndReplaysUnchanged() {
        harness = ApprovalTurnHarness.builder().tool(createFile()).responder(request -> ScriptedLlm.text("Hello.")).build();

        harness.chat("hi");
        harness.chat("hi again");

        assertAll(
                () -> assertNull(harness.storedTranscript().get(1).tools()),
                () -> assertEquals("Hello.", assistantMessagesOfTheLastRequest().get(0).getText()));
    }

    @Test
    void withTheLedgerSwitchedOffHistoryIsTextOnlyAsItAlwaysWas() {
        harness =
                ApprovalTurnHarness.builder()
                        .toolLedger(p -> p.setEnabled(false))
                        .tool(createFile())
                        .responder(fileTask())
                        .build();

        harness.chat(REPORT);
        String answer = harness.chat(ASK);

        assertAll(
                () -> assertEquals("I do not know which file you mean.", answer, "the control: the id never reached the model"),
                () -> assertNull(harness.storedTranscript().get(1).tools()));
    }

    @Test
    void storedButNotReplayedWhenReplayIsSwitchedOff() {
        harness =
                ApprovalTurnHarness.builder()
                        .toolLedger(p -> p.setReplayEnabled(false))
                        .tool(createFile())
                        .responder(fileTask())
                        .build();

        harness.chat(REPORT);
        harness.chat(ASK);

        assertAll(
                () -> assertNotNull(harness.storedTranscript().get(1).tools()),
                () -> assertFalse(harness.llm().lastRequest().mentions("file-42")));
    }

    @Test
    void aBufferedDepthStoresTheSameRecordAsTheStreamedOne() {
        harness =
                ApprovalTurnHarness.builder()
                        .depth(ReasoningDepth.CAREFUL)
                        .tool(createFile())
                        .responder(fileTask())
                        .build();

        harness.chat(REPORT);
        String answer = harness.chat(ASK);

        ChatTurn assistant = harness.storedTranscript().get(1);
        assertAll(
                () -> assertEquals("The file id is file-42.", answer),
                () -> assertNotNull(assistant.tools(), "a CAREFUL turn keeps its tool record too"),
                () -> assertEquals("create_file", assistant.tools().entries().get(0).tool()));
    }

    @Test
    void aCallHeldForApprovalIsRecordedAsWaitingAndTheNextTurnIsToldSo() {
        harness =
                ApprovalTurnHarness.builder()
                        .tool(RecordingTool.returning("send_invoice", "Sends an invoice", "sent"))
                        .gateTool("send_invoice")
                        .responder(
                                request -> {
                                    if (request.hasToolResult()) {
                                        return ScriptedLlm.text("It is waiting for approval.");
                                    }
                                    String last = request.userTexts().get(request.userTexts().size() - 1);
                                    return last.startsWith("Send")
                                            ? ScriptedLlm.callTool("c1", "send_invoice", "{\"to\":\"billing@acme.test\"}")
                                            : ScriptedLlm.text("Still waiting.");
                                })
                        .build();

        harness.chat("Send the invoice to billing@acme.test");
        String pendingId = String.valueOf(harness.pendingApproval().getId());
        harness.chat("Did that go out?");

        ToolLedger.Entry held = harness.storedTranscript().get(1).tools().entries().get(0);
        String replayed = assistantMessagesOfTheLastRequest().get(0).getText();
        assertAll(
                () -> assertEquals(ToolLedger.Status.AWAITING_APPROVAL, held.status()),
                () -> assertEquals(pendingId, held.approval()),
                () -> assertEquals(0, harness.tool("send_invoice").executions(), "it has not run"),
                () -> assertTrue(replayed.contains("awaiting human approval"), replayed),
                () -> assertTrue(replayed.contains("approval request " + pendingId), replayed));
    }

    @Test
    void aReplyThatFailedAfterToolsRanStillTellsTheNextTurnWhatRan() {
        harness =
                ApprovalTurnHarness.builder()
                        .realErrorTranslation()
                        .tool(createFile())
                        .responder(
                                request -> {
                                    if (request.hasToolResult()) {
                                        throw new IllegalStateException("provider connection reset");
                                    }
                                    String last = request.userTexts().get(request.userTexts().size() - 1);
                                    return last.equals(REPORT)
                                            ? ScriptedLlm.callTool("c1", "create_file", "{\"to\":\"report.md\"}")
                                            : ScriptedLlm.text("ok");
                                })
                        .build();

        harness.chatFailure(REPORT);
        harness.chat(ASK);

        List<ChatTurn> stored = harness.storedTranscript();
        ChatTurn failed = stored.get(1);
        String replayed = assistantMessagesOfTheLastRequest().get(0).getText();
        assertAll(
                () -> assertTrue(ChatTurn.isFailedReply(failed), "kept for the person as a failed reply: " + failed),
                () -> assertNotNull(failed.tools(), "with what ran before it failed"),
                () -> assertTrue(replayed.contains("create_file") && replayed.contains("file-42"), replayed),
                () -> assertTrue(replayed.contains("failed"), "and that the reply failed: " + replayed),
                () -> assertFalse(replayed.contains("provider connection reset"), "never the error text as something it said: " + replayed));
    }

    @Test
    void secretsInArgumentsAndResultsAreMaskedBeforeTheyAreStoredOrReplayed() {
        String secret = "sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789";
        harness =
                ApprovalTurnHarness.builder()
                        .tool(RecordingTool.returning("create_file", "Creates a file", "{\"note\":\"token " + secret + " saved\"}"))
                        .responder(fileTask())
                        .build();

        harness.chat(REPORT);
        harness.chat(ASK);

        ToolLedger.Entry entry = harness.storedTranscript().get(1).tools().entries().get(0);
        String replayed = assistantMessagesOfTheLastRequest().get(0).getText();
        assertAll(
                () -> assertFalse(entry.result().contains("AbCdEfGhIjKlMn"), "stored: " + entry.result()),
                () -> assertTrue(entry.result().contains("saved"), "the rest of the result is kept: " + entry.result()),
                () -> assertFalse(replayed.contains("AbCdEfGhIjKlMn"), "replayed to the model: " + replayed));
    }

    @Test
    void theRecordLivesInTheTenantScopedSessionEntryAndNeverUnderTheRawClientSessionId() {
        harness = ApprovalTurnHarness.builder().tool(createFile()).responder(fileTask()).build();

        harness.chat(REPORT);

        assertAll(
                () -> assertTrue(harness.historyUnderRawSessionId().isEmpty(), "SEC-004: nothing is written under the raw client id"),
                () -> assertNotNull(harness.historyTheNextTurnWillSee().get(1).tools(), "the scoped entry carries it"),
                () -> assertTrue(harness.redis().entries("agentic:session:" + harness.scopedMemoryKey()).get(1).contains("create_file")),
                () -> assertTrue(harness.redis().listKeys().contains("agentic:session:" + harness.scopedMemoryKey()), harness.redis().listKeys().toString()),
                () -> assertFalse(harness.redis().listKeys().contains("agentic:session:" + harness.sessionId()), "no raw-id list"));
    }
}
