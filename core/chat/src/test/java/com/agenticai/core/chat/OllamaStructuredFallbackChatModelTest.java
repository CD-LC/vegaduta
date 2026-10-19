package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import reactor.core.publisher.Flux;

/**
 * The structured-output path for an Ollama model whose runner refuses {@code json_schema} (HTTP 400
 * "structured output is unavailable", Ollama 0.19 MLX runner with qwen3.5:4b): the call must
 * degrade to prompt-based JSON with validation and one repair turn, never fail.
 */
class OllamaStructuredFallbackChatModelTest {

    private static final String MODEL = "refuses-schema-model";
    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"score\":{\"type\":\"integer\"}},"
                    + "\"required\":[\"title\",\"score\"]}";

    @AfterEach
    void forget() {
        OllamaStructuredFallbackChatModel.FORMAT_REFUSED.remove(MODEL);
    }

    /** Scripted provider: each call is answered by the next function; records every prompt. */
    private static final class Scripted implements ChatModel {
        final List<Prompt> prompts = new ArrayList<>();
        private final List<Function<Prompt, ChatResponse>> script;

        @SafeVarargs
        Scripted(Function<Prompt, ChatResponse>... script) {
            this.script = List.of(script);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return script.get(Math.min(prompts.size(), script.size()) - 1).apply(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return null;
        }
    }

    private static ChatResponse reply(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static Function<Prompt, ChatResponse> refusal() {
        return p -> {
            throw new HttpClientErrorException(
                    HttpStatus.BAD_REQUEST,
                    "Bad Request",
                    "{\"error\":{\"message\":\"structured output is unavailable\"}}"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    null);
        };
    }

    private static Prompt schemaPrompt() {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(MODEL)
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(ResponseFormat.JsonSchema.builder()
                                .name("answer")
                                .schema(SCHEMA)
                                .strict(false)
                                .build())
                        .build())
                .build();
        return new Prompt(List.of(new SystemMessage("You are terse."), new UserMessage("Rate it.")), options);
    }

    private OllamaStructuredFallbackChatModel wrap(ChatModel provider) {
        return new OllamaStructuredFallbackChatModel(provider, SCHEMA);
    }

    @Test
    void aServerThatHonoursTheSchemaIsCalledOnceAndUntouched() {
        Scripted provider = new Scripted(p -> reply("{\"title\":\"x\",\"score\":3}"));

        ChatResponse out = wrap(provider).call(schemaPrompt());

        assertEquals("{\"title\":\"x\",\"score\":3}", out.getResult().getOutput().getText());
        assertEquals(1, provider.prompts.size());
        assertNotNull(((OpenAiChatOptions) provider.prompts.get(0).getOptions()).getResponseFormat());
    }

    @Test
    void aRefusedSchemaFallsBackToPromptBasedJsonAndReturnsCleanJson() {
        Scripted provider = new Scripted(refusal(), p -> reply("Sure!\n```json\n{\"title\":\"x\",\"score\":3}\n```"));

        ChatResponse out = wrap(provider).call(schemaPrompt());

        assertEquals("{\"title\":\"x\",\"score\":3}", out.getResult().getOutput().getText());
        assertEquals(2, provider.prompts.size());
        Prompt plain = provider.prompts.get(1);
        assertNull(((OpenAiChatOptions) plain.getOptions()).getResponseFormat(), "no response format on the retry");
        SystemMessage system = (SystemMessage) plain.getInstructions().get(0);
        assertTrue(system.getText().startsWith("You are terse."), "the caller's system message is kept");
        assertTrue(system.getText().contains("\"required\""), "the schema is written into the system message");
        assertEquals(2, plain.getInstructions().size(), "no extra messages when the first reply fits");
        assertTrue(OllamaStructuredFallbackChatModel.FORMAT_REFUSED.contains(MODEL));
    }

    @Test
    void aWrongShapeGetsExactlyOneRepairTurnThatNamesTheProblem() {
        Scripted provider = new Scripted(
                refusal(), p -> reply("{\"title\":\"x\"}"), p -> reply("{\"title\":\"x\",\"score\":7}"));

        ChatResponse out = wrap(provider).call(schemaPrompt());

        assertEquals("{\"title\":\"x\",\"score\":7}", out.getResult().getOutput().getText());
        assertEquals(3, provider.prompts.size());
        List<org.springframework.ai.chat.messages.Message> repair = provider.prompts.get(2).getInstructions();
        assertTrue(repair.get(repair.size() - 1) instanceof UserMessage);
        String ask = ((UserMessage) repair.get(repair.size() - 1)).getText();
        assertTrue(ask.contains("$.score is required"), ask);
        assertTrue(repair.get(repair.size() - 2) instanceof AssistantMessage, "the bad reply is in the conversation");
    }

    @Test
    void aReplyThatStaysWrongIsReturnedAsIsInsteadOfFailing() {
        Scripted provider = new Scripted(refusal(), p -> reply("I cannot."), p -> reply("still prose"));

        ChatResponse out = wrap(provider).call(schemaPrompt());

        assertEquals("still prose", out.getResult().getOutput().getText());
        assertEquals(3, provider.prompts.size(), "one retry plus one repair, never more");
    }

    @Test
    void aRefusingModelIsRememberedAndNotAskedForTheSchemaAgain() {
        Scripted provider = new Scripted(refusal(), p -> reply("{\"title\":\"a\",\"score\":1}"), p -> reply("{\"title\":\"b\",\"score\":2}"));
        OllamaStructuredFallbackChatModel model = wrap(provider);

        model.call(schemaPrompt());
        ChatResponse second = model.call(schemaPrompt());

        assertEquals("{\"title\":\"b\",\"score\":2}", second.getResult().getOutput().getText());
        assertEquals(3, provider.prompts.size(), "the second call goes straight to the prompt-based path");
        assertNull(((OpenAiChatOptions) provider.prompts.get(2).getOptions()).getResponseFormat());
    }

    @Test
    void anErrorThatIsNotAboutTheSchemaStillFails() {
        Scripted provider = new Scripted(p -> {
            throw new HttpClientErrorException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        });

        assertThrows(HttpClientErrorException.class, () -> wrap(provider).call(schemaPrompt()));
        assertEquals(1, provider.prompts.size());
    }

    @Test
    void aRequestWithoutAJsonSchemaFormatPassesStraightThrough() {
        Scripted provider = new Scripted(p -> reply("hello"));
        Prompt plain = new Prompt(List.of(new UserMessage("hi")), OpenAiChatOptions.builder().model(MODEL).build());

        ChatResponse out = wrap(provider).call(plain);

        assertEquals("hello", out.getResult().getOutput().getText());
        assertEquals(1, provider.prompts.size());
    }

    /**
     * The production wiring hands the wrapper to a ChatClient, which is what builds the Prompt. The
     * model's default options (where the factory puts the response format) must reach the wrapper
     * on that prompt, or the fallback would never trigger outside unit tests.
     */
    @Test
    void theFallbackFiresWhenTheSchemaIsOnlyInTheModelsDefaultOptions() {
        OpenAiChatOptions defaults = (OpenAiChatOptions) schemaPrompt().getOptions();
        List<Prompt> seen = new ArrayList<>();
        ChatModel provider = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                seen.add(prompt);
                if (seen.size() == 1) {
                    throw new HttpClientErrorException(
                            HttpStatus.BAD_REQUEST,
                            "Bad Request",
                            "{\"error\":\"structured output is unavailable\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            null);
                }
                return reply("{\"title\":\"x\",\"score\":3}");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(call(prompt));
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return defaults;
            }
        };

        String content = org.springframework.ai.chat.client.ChatClient.builder(wrap(provider))
                .build()
                .prompt()
                .user("Rate it.")
                .call()
                .content();

        assertEquals("{\"title\":\"x\",\"score\":3}", content);
        assertEquals(2, seen.size(), "refused once, then the prompt-based retry");
    }
}
