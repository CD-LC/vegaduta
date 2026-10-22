package com.agenticai.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.function.FunctionToolCallback;
import reactor.core.publisher.Flux;

class AnthropicThinkingChatModelTest {

    private static Generation thinking(String text) {
        return new Generation(
                AssistantMessage.builder()
                        .content(text)
                        .properties(Map.of("signature", "sig"))
                        .build(),
                ChatGenerationMetadata.builder().finishReason("end_turn").build());
    }

    private static Generation text(String text) {
        return new Generation(
                AssistantMessage.builder().content(text).build(),
                ChatGenerationMetadata.builder().finishReason("end_turn").build());
    }

    /** ChatModel stub returning a fixed response and counting calls. */
    private static final class Stub implements ChatModel {
        final ChatResponse response;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Prompt> lastPrompt = new AtomicReference<>();
        final ChatOptions defaults;

        Stub(ChatResponse response) {
            this(response, null);
        }

        Stub(ChatResponse response, ChatOptions defaults) {
            this.response = response;
            this.defaults = defaults;
        }

        @Override
        public ChatOptions getOptions() {
            return defaults != null ? defaults : ChatModel.super.getOptions();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            lastPrompt.set(prompt);
            return response;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            calls.incrementAndGet();
            lastPrompt.set(prompt);
            return Flux.just(response);
        }
    }

    @Test
    void replyIsFinalTextNotThinkingWhenThinkingIsFirstGeneration() {
        ChatResponse raw = new ChatResponse(List.of(thinking("let me reason"), text("the answer")));
        assertThat(raw.getResult().getOutput().getText()).isEqualTo("let me reason");

        Stub thinkingModel = new Stub(raw);
        var model = new AnthropicThinkingChatModel(thinkingModel, new Stub(raw));
        ChatResponse out = model.call(new Prompt(new UserMessage("hi")));

        assertThat(out.getResults()).hasSize(1);
        assertThat(out.getResult().getOutput().getText()).isEqualTo("the answer");
    }

    @Test
    void redactedThinkingIsAlsoRemoved() {
        Generation redacted =
                new Generation(
                        AssistantMessage.builder()
                                .content("")
                                .properties(Map.of("data", "opaque"))
                                .build());
        ChatResponse out =
                AnthropicThinkingChatModel.stripThinking(
                        new ChatResponse(List.of(redacted, text("ok"))));
        assertThat(out.getResult().getOutput().getText()).isEqualTo("ok");
    }

    @Test
    void thinkingOnlyResponseKeepsAnEmptyGenerationWithMetadata() {
        ChatResponse out =
                AnthropicThinkingChatModel.stripThinking(
                        new ChatResponse(List.of(thinking("cut off"))));
        assertThat(out.getResults()).hasSize(1);
        assertThat(out.getResult().getOutput().getText()).isEmpty();
        assertThat(out.getResult().getMetadata().getFinishReason()).isEqualTo("end_turn");
    }

    @Test
    void responseWithoutThinkingIsReturnedUntouched() {
        ChatResponse plain = new ChatResponse(List.of(text("hello")));
        assertThat(AnthropicThinkingChatModel.stripThinking(plain)).isSameAs(plain);
    }

    @Test
    void streamingChunksAreStripped() {
        ChatResponse raw = new ChatResponse(List.of(thinking("reasoning"), text("final")));
        var model = new AnthropicThinkingChatModel(new Stub(raw), new Stub(raw));
        List<ChatResponse> chunks =
                model.stream(new Prompt(new UserMessage("hi"))).collectList().block();
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getResult().getOutput().getText()).isEqualTo("final");
    }

    @Test
    void toolCarryingRequestRoutesToThinkingOffModelAndIsNotStripped() {
        ChatResponse toolTurn = new ChatResponse(List.of(text("tool turn reply")));
        Stub thinkingModel =
                new Stub(new ChatResponse(List.of(thinking("x"), text("should not be used"))));
        Stub plainModel = new Stub(toolTurn);
        var model = new AnthropicThinkingChatModel(thinkingModel, plainModel);

        Prompt withTools =
                new Prompt(
                        List.of(new UserMessage("hi")),
                        ToolCallingChatOptions.builder()
                                .toolCallbacks(
                                        FunctionToolCallback.<String, String>builder(
                                                        "web_search", in -> in)
                                                .description("search")
                                                .inputType(String.class)
                                                .build())
                                .build());
        assertThat(AnthropicThinkingChatModel.hasTools(withTools)).isTrue();
        assertThat(model.call(withTools)).isSameAs(toolTurn);
        assertThat(model.stream(withTools).blockFirst()).isSameAs(toolTurn);
        assertThat(thinkingModel.calls.get()).isZero();
        assertThat(plainModel.calls.get()).isEqualTo(2);
    }

    @Test
    void noToolsMeansThinkingModelIsUsed() {
        Prompt noTools =
                new Prompt(
                        List.of(new UserMessage("hi")), ToolCallingChatOptions.builder().build());
        assertThat(AnthropicThinkingChatModel.hasTools(noTools)).isFalse();
        assertThat(AnthropicThinkingChatModel.hasTools(new Prompt("plain"))).isFalse();
    }

    @Test
    void flagOffRequestOptionsAreUnchanged() {
        // Flag off => budget null => the factory builds the plain model with no wrapper, and the
        // options builder must be exactly the pre-existing temperature/top_p behaviour.
        assertThat(AnthropicThinking.budgetFor("claude-sonnet-4-5", "", 16000)).isNull();
        assertThat(AnthropicThinking.budgetFor("claude-sonnet-4-5", null, 16000)).isNull();
        var off =
                AnthropicAgentChatClientFactory.samplingBuilder(
                                "claude-sonnet-4-5", 16000, 0.3, 1.0, SamplingOptions.NONE, null)
                        .build();
        assertThat(off.getThinking()).isNull();
        assertThat(off.getTemperature()).isEqualTo(0.3);
        assertThat(off.getMaxTokens()).isEqualTo(16000);
        assertThat(off.getModel()).isEqualTo("claude-sonnet-4-5");
    }

    private static AnthropicChatOptions plainDefaults() {
        return AnthropicChatOptions.builder().model("m").maxTokens(1000).temperature(0.3).build();
    }

    private static AnthropicChatOptions thinkingDefaults() {
        return AnthropicChatOptions.builder()
                .model("m")
                .maxTokens(20000)
                .thinkingEnabled(1024)
                .build();
    }

    /**
     * Drives a real ChatClient so the default-options copy (the path that defeated routing) runs.
     */
    @Test
    void chatClientWithToolsReachesPlainModelWithThinkingCleared() {
        Stub thinkingModel =
                new Stub(new ChatResponse(List.of(thinking("x"), text("no"))), thinkingDefaults());
        Stub plainModel = new Stub(new ChatResponse(List.of(text("tool turn"))), plainDefaults());
        var wrapper = new AnthropicThinkingChatModel(thinkingModel, plainModel);

        var tool =
                FunctionToolCallback.<String, String>builder("echo", in -> in)
                        .description("echo")
                        .inputType(String.class)
                        .build();
        ChatClient.builder(wrapper)
                .build()
                .prompt()
                .user("hi")
                .toolCallbacks(tool)
                .call()
                .chatResponse();

        assertThat(thinkingModel.calls.get()).isZero();
        assertThat(plainModel.calls.get()).isEqualTo(1);
        var opts = (AnthropicChatOptions) plainModel.lastPrompt.get().getOptions();
        assertThat(opts.getThinking()).isNull();
        assertThat(opts.getTemperature()).isEqualTo(0.3);
    }

    @Test
    void chatClientWithoutToolsReachesThinkingModelWithThinkingAndNoSampling() {
        Stub thinkingModel =
                new Stub(
                        new ChatResponse(List.of(thinking("r"), text("final"))),
                        thinkingDefaults());
        Stub plainModel = new Stub(new ChatResponse(List.of(text("no"))), plainDefaults());
        var wrapper = new AnthropicThinkingChatModel(thinkingModel, plainModel);

        String reply = ChatClient.builder(wrapper).build().prompt().user("hi").call().content();

        assertThat(reply).isEqualTo("final");
        assertThat(plainModel.calls.get()).isZero();
        var opts = (AnthropicChatOptions) thinkingModel.lastPrompt.get().getOptions();
        assertThat(opts.getThinking()).isNotNull();
        assertThat(opts.getTemperature()).isNull();
        assertThat(opts.getMaxTokens()).isEqualTo(20000);
    }

    @Test
    void defaultOptionsAreThePlainThinkingOffOptions() {
        var wrapper =
                new AnthropicThinkingChatModel(
                        new Stub(new ChatResponse(List.of(text("a"))), thinkingDefaults()),
                        new Stub(new ChatResponse(List.of(text("b"))), plainDefaults()));
        assertThat(((AnthropicChatOptions) wrapper.getOptions()).getThinking()).isNull();
    }

    /**
     * Pins the real SDK-model (Spring AI 2.0.1) metadata keys: THINKING/THINKING_DELTA use
     * "signature", redacted uses "data".
     */
    @Test
    void streamingThinkingChunkWithEmptySignatureIsStillStripped() {
        var props = new java.util.HashMap<String, Object>();
        props.put(
                "signature",
                ""); // the SDK model always puts the key even for a thinking_delta chunk (value may
        // be empty)
        Generation delta =
                new Generation(
                        AssistantMessage.builder()
                                .content("partial reasoning")
                                .properties(props)
                                .build());
        assertThat(AnthropicThinkingChatModel.isThinkingGeneration(delta)).isTrue();
    }

    @Test
    void flagOffOptionsAreDeterministicAndCarryNoThinking() {
        var a =
                AnthropicAgentChatClientFactory.samplingBuilder(
                                "claude-sonnet-4-5", 16000, 0.3, 1.0, SamplingOptions.NONE, null)
                        .build();
        var b =
                AnthropicAgentChatClientFactory.samplingBuilder(
                                "claude-sonnet-4-5", 16000, 0.3, 1.0, SamplingOptions.NONE, null)
                        .build();
        assertThat(a).isEqualTo(b);
        assertThat(a.getThinking()).isNull();
    }
}
