package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.memory.TranscriptView;
import com.agenticai.core.agents.turn.ChatFinishProperties;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * What a chat turn does with the provider's FINISH REASON, run through the real turn pipeline (the
 * real {@code AgentChatService}, ChatClient, advisor chain, Spring AI's tool loop, session memory and
 * the dispatcher's cache writes); only the LLM provider is scripted, and it reports its finish reason
 * the way providers do (on the last chunk of the stream / on the response).
 *
 * <p>The failures these pin: a reply cut off at the output limit was streamed, stored and cached as
 * complete; a reply the provider's safety system stopped surfaced as "could you try rephrasing";
 * an empty or interrupted reply was cached and stored as something the agent said.
 */
class FinishReasonTurnE2ETest {

    private static final String QUESTION = "Walk me through setting up billing.";

    private final LlmResponseCacheService cache = mock(LlmResponseCacheService.class);
    private final LlmQualityJudgeService judge = mock(LlmQualityJudgeService.class);
    private ApprovalTurnHarness harness;

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    private ApprovalTurnHarness.Builder base(ScriptedLlm.Responder responder) {
        return ApprovalTurnHarness.builder().responder(responder).responseCache(cache).judge(judge);
    }

    private static ScriptedLlm.Responder replies(ScriptedLlm.Reply... replies) {
        return request -> {
            if (request.number() > replies.length) {
                throw new IllegalStateException(
                        "scripted model called " + request.number() + " times but has " + replies.length + " replies");
            }
            return replies[request.number() - 1];
        };
    }

    private ChatTurn lastAssistantTurn() {
        List<ChatTurn> stored = harness.storedTranscript();
        for (int i = stored.size() - 1; i >= 0; i--) {
            if (stored.get(i).role() == MessageRole.ASSISTANT) {
                return stored.get(i);
            }
        }
        throw new AssertionError("no assistant turn stored: " + stored);
    }

    private void assertNeverCachedNorJudged() {
        verify(cache, never()).put(any(), any(), any(), any(), any(), any());
        verify(judge, never()).sampleAsync(any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ cut off at the output limit

    @Test
    void aReplyCutOffAtTheOutputLimitIsContinuedAndThePersonGetsTheWholeAnswer() {
        harness =
                base(replies(
                                ScriptedLlm.truncated("Step one is to open the settings and "),
                                ScriptedLlm.text("choose the billing tab. Step two is to save.")))
                        .build();

        String reply = harness.chat(QUESTION);

        ChatFinishProperties defaults = new ChatFinishProperties();
        String whole = "Step one is to open the settings and choose the billing tab. Step two is to save.";
        assertAll(
                () -> assertEquals(whole, reply, "the pieces are joined with no gap and no notice"),
                () -> assertEquals(2, harness.llm().modelCalls()),
                () ->
                        assertTrue(
                                harness.llm().requests().get(1).conversation().contains("[ASSISTANT] Step one is to open the settings and "),
                                "the model is shown what it already wrote: " + harness.llm().requests().get(1).conversation()),
                () ->
                        assertTrue(
                                harness.llm().requests().get(1).conversation().contains(defaults.getContinueNudge()),
                                "and told to continue: " + harness.llm().requests().get(1).conversation()),
                () -> assertEquals(whole, lastAssistantTurn().content(), "stored as the complete answer"),
                () -> verify(cache).put(any(), any(), any(), eq(QUESTION), eq(""), eq(whole)),
                () -> verify(judge).sampleAsync(any(), any(), any(), eq(QUESTION), eq(whole)));
    }

    @Test
    void theContinuationPassOffersNoToolsSoACutOffAnswerCannotRerunAWrite() {
        RecordingTool lookup = RecordingTool.returning("lookup", "finds a thing", "found it");
        harness =
                base(replies(
                                ScriptedLlm.callTool("c1", "lookup", "{\"to\":\"billing\"}"),
                                ScriptedLlm.truncated("The billing page is under "),
                                ScriptedLlm.text("Settings.")))
                        .tool(lookup)
                        .build();

        String reply = harness.chat(QUESTION);

        assertAll(
                () -> assertEquals("The billing page is under Settings.", reply),
                () -> assertEquals(1, lookup.executions(), "the tool ran once, in the first pass only"),
                () -> assertFalse(harness.llm().requests().get(0).offeredTools().isEmpty()),
                () ->
                        assertTrue(
                                harness.llm().requests().get(2).offeredTools().isEmpty(),
                                "finishing prose needs no tools; offering them risks a repeat of the side effects"));
    }

    @Test
    void aReplyThatStaysCutOffEndsHonestlyAndIsNeitherCachedNorStoredAsComplete() {
        harness =
                base(request -> ScriptedLlm.truncated("part" + request.number() + " "))
                        .finish(p -> p.setMaxContinuations(2))
                        .build();

        String reply = harness.chat(QUESTION);

        ChatFinishProperties defaults = new ChatFinishProperties();
        ChatTurn stored = lastAssistantTurn();
        assertAll(
                () -> assertEquals(3, harness.llm().modelCalls(), "one pass plus the two continuations allowed"),
                () -> assertTrue(reply.startsWith("part1 part2 part3 "), reply),
                () -> assertTrue(reply.endsWith(defaults.getTruncatedNotice()), "the person is told: " + reply),
                () -> assertEquals(TranscriptView.STATUS_INTERRUPTED, TranscriptView.statusOf(stored.role(), stored.content())),
                () -> assertEquals("part1 part2 part3 ", TranscriptView.textOf(stored.role(), stored.content())),
                () -> assertNeverCachedNorJudged());
    }

    @Test
    void withNoContinuationsAllowedACutOffReplyIsReportedAtOnce() {
        harness =
                base(replies(ScriptedLlm.truncated("A long answer that")))
                        .finish(p -> p.setMaxContinuations(0))
                        .build();

        String reply = harness.chat(QUESTION);

        assertAll(
                () -> assertEquals(1, harness.llm().modelCalls()),
                () -> assertTrue(reply.contains(new ChatFinishProperties().getTruncatedNotice()), reply),
                () -> assertNeverCachedNorJudged());
    }

    @Test
    void theNoticesComeFromConfigurationNotFromCode() {
        harness =
                base(replies(ScriptedLlm.truncated("A long answer that")))
                        .finish(p -> {
                            p.setMaxContinuations(0);
                            p.setTruncatedNotice("<<CUT-OFF>>");
                        })
                        .build();

        assertTrue(harness.chat(QUESTION).endsWith("A long answer that\n\n<<CUT-OFF>>"));
    }

    @Test
    void withFinishHandlingSwitchedOffAReplyIsServedAsBefore() {
        harness =
                base(replies(ScriptedLlm.truncated("A long answer that")))
                        .finish(p -> p.setEnabled(false))
                        .build();

        String reply = harness.chat(QUESTION);

        assertAll(
                () -> assertEquals("A long answer that", reply),
                () -> assertEquals(1, harness.llm().modelCalls()));
    }

    // ----------------------------------------------------------------------------- safety stop

    @Test
    void aSafetyStopSaysSoInsteadOfAskingThePersonToRephrase() {
        harness = base(replies(ScriptedLlm.safetyStopped(""))).build();

        String reply = harness.chat(QUESTION);

        ChatTurn stored = lastAssistantTurn();
        assertAll(
                () -> assertEquals(1, harness.llm().modelCalls(), "a refusal is not retried"),
                () -> assertEquals(new ChatFinishProperties().renderRefusalNotice("content_filter"), reply),
                () -> assertTrue(reply.contains("safety system"), reply),
                () -> assertFalse(reply.toLowerCase().contains("rephras"), reply),
                () -> assertTrue(ChatTurn.isFailedReply(stored), "stored as a failed reply: " + stored.content()),
                () -> assertTrue(stored.content().contains("safety system")),
                () -> assertNeverCachedNorJudged());
    }

    @Test
    void aSafetyStopAfterSomeTextKeepsTheTextAndAddsTheNotice() {
        harness = base(replies(ScriptedLlm.safetyStopped("Here is how to begin"))).build();

        String reply = harness.chat(QUESTION);

        ChatTurn stored = lastAssistantTurn();
        assertAll(
                () ->
                        assertEquals(
                                "Here is how to begin\n\n" + new ChatFinishProperties().renderRefusalNotice("content_filter"),
                                reply),
                () -> assertEquals(TranscriptView.STATUS_INTERRUPTED, TranscriptView.statusOf(stored.role(), stored.content())),
                () -> assertEquals("Here is how to begin", TranscriptView.textOf(stored.role(), stored.content())),
                () -> assertNeverCachedNorJudged());
    }

    @Test
    void theProvidersOwnReasonIsPassedIntoTheRefusalNotice() {
        harness =
                base(replies(new ScriptedLlm.Reply("", List.of(), "SAFETY")))
                        .finish(p -> p.setRefusalNotice("BLOCKED [{reason}]"))
                        .build();

        assertEquals("BLOCKED [SAFETY]", harness.chat(QUESTION));
    }

    // ------------------------------------------------------------------------------ empty reply

    @Test
    void anEmptyReplyUsesTheConfiguredLineAndIsNeitherCachedNorStoredAsAnAnswer() {
        harness =
                base(replies(ScriptedLlm.text("")))
                        .finish(p -> p.setEmptyReplyMessage("Nothing came back from the model."))
                        .build();

        String reply = harness.chat(QUESTION);

        ChatTurn stored = lastAssistantTurn();
        assertAll(
                () -> assertEquals("Nothing came back from the model.", reply),
                () -> assertTrue(ChatTurn.isFailedReply(stored), stored.content()),
                () -> assertNeverCachedNorJudged());
    }

    // ---------------------------------------------------------------------- interrupted reply

    @Test
    void aReplyClosedAfterAFailureIsNeverCached() {
        RecordingTool lookup = RecordingTool.returning("lookup", "finds a thing", "found it");
        harness =
                base(request -> {
                            if (request.number() == 1) {
                                return new ScriptedLlm.Reply(
                                        "Checking that now",
                                        List.of(new AssistantMessage.ToolCall("c1", "function", "lookup", "{}")),
                                        "tool_calls");
                            }
                            throw new IllegalStateException("provider went away");
                        })
                        .tool(lookup)
                        .build();

        String reply = harness.chat(QUESTION);

        assertAll(
                () -> assertTrue(reply.startsWith("Checking that now"), reply),
                () -> assertTrue(reply.endsWith(ChatTurn.INTERRUPTED_REPLY_SUFFIX), reply),
                () -> assertNeverCachedNorJudged());
    }

    // ------------------------------------------------------------------ the normal path is unchanged

    @Test
    void aFinishedReplyIsStoredCachedAndJudgedExactlyAsBefore() {
        harness = base(replies(ScriptedLlm.text("Open Settings, then Billing."))).build();

        String reply = harness.chat(QUESTION);

        ChatTurn stored = lastAssistantTurn();
        assertAll(
                () -> assertEquals("Open Settings, then Billing.", reply),
                () -> assertEquals("Open Settings, then Billing.", stored.content()),
                () -> assertEquals(TranscriptView.STATUS_OK, TranscriptView.statusOf(stored.role(), stored.content())),
                () -> verify(cache).put(any(), any(), any(), eq(QUESTION), eq(""), eq("Open Settings, then Billing.")),
                () -> verify(judge).sampleAsync(any(), any(), any(), eq(QUESTION), eq("Open Settings, then Billing.")),
                () -> assertEquals(1, harness.llm().modelCalls()));
    }

    // ------------------------------------------------------------------------ malformed tool call

    @Test
    void aMalformedToolCallIsRetriedOnceWithTheToolsStillOffered() {
        RecordingTool lookup = RecordingTool.returning("lookup", "finds a thing", "found it");
        harness =
                base(replies(
                                new ScriptedLlm.Reply("", List.of(), "MALFORMED_FUNCTION_CALL"),
                                ScriptedLlm.text("Done after the retry.")))
                        .tool(lookup)
                        // An agent that offers tools is not cacheable by default (app.chat.cache.*); this test is
                        // about the retry's reply being a finished answer, so it opts back in.
                        .cache(p -> p.setEligibleWithTools(true))
                        .build();

        String reply = harness.chat(QUESTION);

        assertAll(
                () -> assertEquals("Done after the retry.", reply),
                () -> assertEquals(2, harness.llm().modelCalls()),
                () -> assertTrue(harness.llm().requests().get(1).conversation().contains(new ChatFinishProperties().getRetryToolCallNudge()), harness.llm().requests().get(1).conversation()),
                () -> assertFalse(harness.llm().requests().get(1).offeredTools().isEmpty(), "the retry is about calling the tool"),
                () -> verify(cache).put(any(), any(), any(), eq(QUESTION), eq(""), eq("Done after the retry.")));
    }

    @Test
    void aToolCallThatStaysMalformedEndsWithAnHonestMessage() {
        RecordingTool lookup = RecordingTool.returning("lookup", "finds a thing", "found it");
        harness =
                base(request -> new ScriptedLlm.Reply("", List.of(), "MALFORMED_FUNCTION_CALL"))
                        .tool(lookup)
                        .build();

        String reply = harness.chat(QUESTION);

        assertAll(
                () -> assertEquals(2, harness.llm().modelCalls(), "one pass plus the one retry allowed"),
                () -> assertEquals(new ChatFinishProperties().getMalformedToolCallNotice(), reply),
                () -> assertTrue(ChatTurn.isFailedReply(lastAssistantTurn())),
                () -> assertNeverCachedNorJudged());
    }

    // ----------------------------------------------------------------------- buffered depths

    @Test
    void aBufferedDepthContinuesACutOffReplyToo() {
        harness =
                base(replies(
                                ScriptedLlm.truncated("Step one is to open the settings and "),
                                ScriptedLlm.text("choose the billing tab.")))
                        .depth(ReasoningDepth.CAREFUL)
                        .build();

        String reply = harness.chat(QUESTION);

        String whole = "Step one is to open the settings and choose the billing tab.";
        assertAll(
                () -> assertEquals(whole, reply),
                () -> assertEquals(2, harness.llm().modelCalls()),
                () -> assertTrue(harness.llm().requests().get(1).conversation().contains(new ChatFinishProperties().getContinueNudge())),
                () -> assertEquals(whole, lastAssistantTurn().content()),
                () -> verify(cache).put(any(), any(), any(), eq(QUESTION), eq(""), eq(whole)));
    }

    @Test
    void aBufferedDepthThatStaysCutOffIsReportedAndNotCached() {
        harness =
                base(request -> ScriptedLlm.truncated("part" + request.number() + " "))
                        .depth(ReasoningDepth.CAREFUL)
                        .finish(p -> p.setMaxContinuations(1))
                        .build();

        String reply = harness.chat(QUESTION);

        ChatTurn stored = lastAssistantTurn();
        assertAll(
                () -> assertEquals(2, harness.llm().modelCalls()),
                () -> assertTrue(reply.startsWith("part1 part2 "), reply),
                () -> assertTrue(reply.endsWith(new ChatFinishProperties().getTruncatedNotice()), reply),
                () -> assertEquals(TranscriptView.STATUS_INTERRUPTED, TranscriptView.statusOf(stored.role(), stored.content())),
                () -> assertNeverCachedNorJudged());
    }

    @Test
    void aBufferedDepthSafetyStopSaysSoAndStoresNoAnswer() {
        harness = base(replies(ScriptedLlm.safetyStopped(""))).depth(ReasoningDepth.CAREFUL).build();

        String reply = harness.chat(QUESTION);

        assertAll(
                () -> assertEquals(new ChatFinishProperties().renderRefusalNotice("content_filter"), reply),
                () -> assertTrue(ChatTurn.isFailedReply(lastAssistantTurn())),
                () -> assertNeverCachedNorJudged());
    }

    @Test
    void aBufferedDepthEmptyReplyUsesTheConfiguredLineAndStoresNoAnswer() {
        harness =
                base(replies(ScriptedLlm.text("")))
                        .depth(ReasoningDepth.CAREFUL)
                        .finish(p -> p.setEmptyReplyMessage("Nothing came back from the model."))
                        .build();

        String reply = harness.chat(QUESTION);

        assertAll(
                () -> assertEquals("Nothing came back from the model.", reply),
                () -> assertTrue(ChatTurn.isFailedReply(lastAssistantTurn())),
                () -> assertNeverCachedNorJudged());
    }
}
