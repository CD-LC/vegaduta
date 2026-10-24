package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

/**
 * The exact-match response cache used to key on the single message and to be open to tool turns: "yes" in
 * one conversation was answered with the reply cached for "yes" in another, and a turn that read live data
 * through a tool could be served (or stored) from the cache. Run through the real turn pipeline over a real
 * cache service on an in-memory Redis; only the LLM is scripted.
 */
class ResponseCacheTurnE2ETest {

    private final List<ApprovalTurnHarness> open = new ArrayList<>();
    private final UUID agentId = UUID.randomUUID();

    @AfterEach
    void close() {
        open.forEach(ApprovalTurnHarness::close);
    }

    private ApprovalTurnHarness conversation(LlmResponseCacheService cache, ScriptedLlm.Responder responder, RecordingTool... tools) {
        ApprovalTurnHarness.Builder builder =
                ApprovalTurnHarness.builder()
                        .agentId(agentId)
                        .sessionId("session-" + UUID.randomUUID())
                        .responseCache(cache)
                        .responder(responder);
        for (RecordingTool tool : tools) {
            builder.tool(tool);
        }
        ApprovalTurnHarness harness = builder.build();
        open.add(harness);
        return harness;
    }

    /** Asks a question of its own for each opening message, and answers "yes" according to the question it asked. */
    private static ScriptedLlm.Responder confirmsWhatItAsked() {
        return request -> {
            String last = request.userTexts().get(request.userTexts().size() - 1);
            if (last.equals("Cancel my order")) {
                return ScriptedLlm.text("Shall I cancel the order?");
            }
            if (last.equals("Email me the invoice")) {
                return ScriptedLlm.text("Shall I email the invoice?");
            }
            String lastAssistant =
                    request.messages().stream()
                            .filter(m -> m.getMessageType() == MessageType.ASSISTANT)
                            .map(Message::getText)
                            .reduce("", (a, b) -> b);
            return lastAssistant.contains("cancel")
                    ? ScriptedLlm.text("Order cancelled.")
                    : ScriptedLlm.text("Invoice emailed.");
        };
    }

    private LlmResponseCacheService realCache() {
        return new LlmResponseCacheService(new InMemoryRedis().template(), 6);
    }

    // ------------------------------------------------------------------- keyed on the conversation

    @Test
    void aFollowUpIsNotAnsweredFromAnotherConversationsCachedReply() {
        LlmResponseCacheService cache = realCache();
        ApprovalTurnHarness orderTalk = conversation(cache, confirmsWhatItAsked());
        ApprovalTurnHarness invoiceTalk = conversation(cache, confirmsWhatItAsked());

        orderTalk.chat("Cancel my order");
        String orders = orderTalk.chat("yes");
        invoiceTalk.chat("Email me the invoice");
        String invoices = invoiceTalk.chat("yes");

        assertAll(
                () -> assertEquals("Order cancelled.", orders),
                () -> assertEquals("Invoice emailed.", invoices, "the second conversation's \"yes\" has its own answer"));
    }

    @Test
    void theSameConversationAgainIsServedFromTheCacheWithoutCallingTheModel() {
        LlmResponseCacheService cache = realCache();
        ApprovalTurnHarness first = conversation(cache, confirmsWhatItAsked());
        first.chat("Cancel my order");
        first.chat("yes");

        ApprovalTurnHarness again = conversation(cache, confirmsWhatItAsked());
        String opening = again.chat("Cancel my order");
        String confirmation = again.chat("yes");

        assertAll(
                () -> assertEquals("Shall I cancel the order?", opening),
                () -> assertEquals("Order cancelled.", confirmation),
                () -> assertEquals(0, again.llm().modelCalls(), "both turns were cache hits: the first message has no context to key on, the follow-up has the same one"));
    }

    @Test
    void withTheConversationKeySwitchedOffTheKeyIsTheMessageAloneAsItAlwaysWas() {
        LlmResponseCacheService cache = mock(LlmResponseCacheService.class);
        ApprovalTurnHarness harness =
                ApprovalTurnHarness.builder()
                        .agentId(agentId)
                        .responseCache(cache)
                        .cache(p -> p.setKeyOnConversation(false))
                        .responder(confirmsWhatItAsked())
                        .build();
        open.add(harness);

        harness.chat("Cancel my order");
        harness.chat("yes");

        verify(cache).get(anyString(), any(), anyString(), org.mockito.ArgumentMatchers.eq("yes"), org.mockito.ArgumentMatchers.eq(""));
    }

    // ------------------------------------------------------------------- not for turns that use tools

    private static RecordingTool lookup() {
        return RecordingTool.returning("lookup_stock", "Looks up live stock levels", "{\"stock\":12}");
    }

    @Test
    void anAgentThatOffersToolsNeverReadsOrWritesTheCache() {
        LlmResponseCacheService cache = mock(LlmResponseCacheService.class);
        ApprovalTurnHarness harness =
                conversation(cache, request -> request.hasToolResult()
                        ? ScriptedLlm.text("There are 12 in stock.")
                        : ScriptedLlm.callTool("c1", "lookup_stock", "{\"sku\":\"A1\"}"), lookup());

        String reply = harness.chat("How many A1 are in stock?");

        assertAll(
                () -> assertEquals("There are 12 in stock.", reply),
                () -> assertEquals(1, harness.tool("lookup_stock").executions()),
                () -> verify(cache, never()).get(any(), any(), any(), any()),
                () -> verify(cache, never()).get(any(), any(), any(), any(), any()),
                () -> verify(cache, never()).put(any(), any(), any(), any(), any()),
                () -> verify(cache, never()).put(any(), any(), any(), any(), any(), any()));
    }

    @Test
    void aToolAnswerFromAnEarlierTurnIsNotServedToTheNextAsker() {
        LlmResponseCacheService cache = realCache();
        ScriptedLlm.Responder responder =
                request -> request.hasToolResult()
                        ? ScriptedLlm.text("There are 12 in stock.")
                        : ScriptedLlm.callTool("c1", "lookup_stock", "{\"sku\":\"A1\"}");
        ApprovalTurnHarness first = conversation(cache, responder, lookup());
        ApprovalTurnHarness second = conversation(cache, responder, lookup());

        first.chat("How many A1 are in stock?");
        second.chat("How many A1 are in stock?");

        assertEquals(1, second.tool("lookup_stock").executions(), "live data is read again, not replayed from the cache");
    }

    @Test
    void aToolFreeAgentStillCachesAsBefore() {
        LlmResponseCacheService cache = mock(LlmResponseCacheService.class);
        ApprovalTurnHarness harness = conversation(cache, request -> ScriptedLlm.text("We open at nine."));

        harness.chat("When do you open?");

        assertAll(
                () -> verify(cache).get(anyString(), any(), anyString(), org.mockito.ArgumentMatchers.eq("When do you open?"), org.mockito.ArgumentMatchers.eq("")),
                () -> verify(cache).put(anyString(), any(), anyString(), org.mockito.ArgumentMatchers.eq("When do you open?"), org.mockito.ArgumentMatchers.eq(""), org.mockito.ArgumentMatchers.eq("We open at nine.")));
    }

    @Test
    void anOperatorCanKeepCachingForAnAgentWithToolsAsBefore() {
        LlmResponseCacheService cache = mock(LlmResponseCacheService.class);
        ApprovalTurnHarness harness =
                ApprovalTurnHarness.builder()
                        .agentId(agentId)
                        .responseCache(cache)
                        .cache(p -> p.setEligibleWithTools(true))
                        .tool(lookup())
                        .responder(request -> ScriptedLlm.text("We open at nine."))
                        .build();
        open.add(harness);

        harness.chat("When do you open?");

        verify(cache).get(anyString(), any(), anyString(), org.mockito.ArgumentMatchers.eq("When do you open?"), org.mockito.ArgumentMatchers.eq(""));
    }

    @Test
    void aReplyThatIsNotAnAnswerIsNeverCached() {
        LlmResponseCacheService cache = mock(LlmResponseCacheService.class);
        ApprovalTurnHarness harness =
                conversation(cache, request -> ScriptedLlm.truncated("We open at"));

        harness.chat("When do you open?");

        verify(cache, never()).put(any(), any(), any(), any(), any(), any());
    }
}
