package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * The application only sees {@code .content()}; this proves the provider's finish reason still
 * reaches it, through a real {@link ChatClient} (real advisor chain) over a {@link ChatModel} that
 * answers the way providers do: a blocking response with the reason on its generation, or a stream
 * whose last data chunk carries it.
 */
class FinishReasonAdvisorTest {

    private static ChatResponse chunk(String text, String finishReason) {
        ChatGenerationMetadata.Builder meta = ChatGenerationMetadata.builder();
        if (finishReason != null) {
            meta.finishReason(finishReason);
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text), meta.build())));
    }

    /** A model that replays fixed responses; a stream replays them as chunks. */
    private static final class FixedModel implements ChatModel {
        private final List<ChatResponse> chunks;
        int calls;

        FixedModel(ChatResponse... chunks) {
            this.chunks = List.of(chunks);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            return chunks.get(chunks.size() - 1);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            calls++;
            return Flux.fromIterable(chunks);
        }
    }

    @Test
    void aBlockingCallReportsTheReasonOfItsResponse() {
        FinishReasonCapture capture = new FinishReasonCapture();
        FixedModel model = new FixedModel(chunk("The answer is cut", "length"));

        String content = ChatClient.create(model).prompt().user("q")
            .advisors(new FinishReasonAdvisor(capture)).call().content();

        assertEquals("The answer is cut", content, "the advisor must not change the reply");
        assertEquals("length", capture.raw());
        assertEquals(FinishReason.LENGTH, capture.reason());
    }

    @Test
    void aStreamReportsTheReasonOfItsLastChunkNotAnEarlierOne() {
        FinishReasonCapture capture = new FinishReasonCapture();
        // Gemini tags every chunk "STOP"; Anthropic/OpenAI put the real reason on the last chunk only,
        // and a tool-using stream carries tool_use on its intermediate round.
        FixedModel model = new FixedModel(
            chunk("I will look", "tool_use"), chunk("It is ", null), chunk("sunny", null), chunk("", "max_tokens"));

        List<String> text = ChatClient.create(model).prompt().user("q")
            .advisors(new FinishReasonAdvisor(capture)).stream().content().collectList().block();

        assertEquals(List.of("I will look", "It is ", "sunny"), text, "chunks pass through untouched");
        assertEquals(FinishReason.LENGTH, capture.reason());
        assertEquals("max_tokens", capture.raw());
    }

    @Test
    void aReplyWithNoReasonIsUnknownAndNeverMistakenForStop() {
        FinishReasonCapture capture = new FinishReasonCapture();
        ChatClient.create(new FixedModel(chunk("hi", null))).prompt().user("q")
            .advisors(new FinishReasonAdvisor(capture)).stream().content().collectList().block();

        assertEquals(FinishReason.UNKNOWN, capture.reason());
        assertNull(capture.raw());
        assertFalse(capture.wasObserved());
    }

    @Test
    void aNewRequestStartsCleanSoARetryIsNotJudgedByTheAttemptBeforeIt() {
        FinishReasonCapture capture = new FinishReasonCapture();
        ChatClient first = ChatClient.create(new FixedModel(chunk("cut", "length")));
        ChatClient second = ChatClient.create(new FixedModel(chunk("fine", "stop")));
        ChatClient silent = ChatClient.create(new FixedModel(chunk("no reason", null)));

        first.prompt().user("q").advisors(new FinishReasonAdvisor(capture)).stream().content().collectList().block();
        assertEquals(FinishReason.LENGTH, capture.reason());
        second.prompt().user("q").advisors(new FinishReasonAdvisor(capture)).stream().content().collectList().block();
        assertEquals(FinishReason.STOP, capture.reason());
        silent.prompt().user("q").advisors(new FinishReasonAdvisor(capture)).stream().content().collectList().block();
        assertEquals(FinishReason.UNKNOWN, capture.reason(), "the previous attempt's reason must not leak into this one");
    }

    @Test
    void theReasonIsResetWhenAStreamIsSubscribedNotWhenItIsAssembled() {
        FinishReasonCapture capture = new FinishReasonCapture();
        FixedModel model = new FixedModel(chunk("x", "length"));
        List<FinishReason> seenWhileAssembling = new ArrayList<>();

        Flux<String> stream = ChatClient.create(model).prompt().user("q")
            .advisors(new FinishReasonAdvisor(capture)).stream().content();
        seenWhileAssembling.add(capture.reason());
        assertEquals(0, model.calls, "nothing runs until subscription");
        stream.blockLast();

        assertEquals(List.of(FinishReason.UNKNOWN), seenWhileAssembling);
        assertTrue(capture.wasObserved());
    }
}
