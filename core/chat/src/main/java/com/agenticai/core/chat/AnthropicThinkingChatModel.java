package com.agenticai.core.chat;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/**
 * Makes Anthropic extended thinking safe on Spring AI 2.0.x (official Anthropic SDK model). Only
 * built when a thinking budget is configured; with thinking off the factory returns the plain model
 * untouched.
 *
 * <p>Two upstream behaviours are handled here (re-verified against spring-ai-anthropic 2.0.1
 * bytecode: the SDK model still emits the thinking block(s) as leading generations and keeps them,
 * replayable, on the final assistant message; whether the replay is accepted on every tool loop is
 * not proven against the live API, so tool turns keep the conservative thinking-off route):
 *
 * <ol>
 *   <li><b>Thinking is the first generation.</b> the SDK model maps every thinking block to its own
 *       {@link Generation}, thinking first, and {@code ChatResponse#getResult()} (hence {@code
 *       ChatClient...content()}) reads generation 0, so the reply became the reasoning text. {@link
 *       #stripThinking} removes thinking generations (marked by a {@code signature} property, or
 *       {@code data} for redacted thinking) so the final text is the result.
 *   <li><b>Thinking must be replayed across tool rounds.</b> Anthropic rejects (400) a
 *       thinking-enabled request that continues after a tool call without the prior thinking block.
 *       Spring AI 2.0.1 carries it on the assistant message, but that path is not proven live here,
 *       so a request that carries tools is routed to {@code plainModel} (thinking off, normal
 *       sampling) with a log line.
 * </ol>
 */
final class AnthropicThinkingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(AnthropicThinkingChatModel.class);

    private final ChatModel thinkingModel;
    private final ChatModel plainModel;

    AnthropicThinkingChatModel(ChatModel thinkingModel, ChatModel plainModel) {
        this.thinkingModel = thinkingModel;
        this.plainModel = plainModel;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (hasTools(prompt)) {
            logToolTurn();
            return plainModel.call(withoutThinking(prompt));
        }
        return stripThinking(thinkingModel.call(withThinking(prompt)));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        if (hasTools(prompt)) {
            logToolTurn();
            return plainModel.stream(withoutThinking(prompt));
        }
        return thinkingModel.stream(withThinking(prompt))
                .map(AnthropicThinkingChatModel::stripThinking);
    }

    /**
     * ChatClient copies {@code getOptions()} into every request and Spring AI merges the request
     * options OVER the model's own defaults, so whatever this returns wins. It must be the
     * thinking-OFF options: otherwise thinking=ENABLED would ride along on tool turns and defeat
     * the routing. Tool-free turns re-apply thinking explicitly in {@link #withThinking}.
     */
    @Override
    public ChatOptions getOptions() {
        // Spring AI 2: getOptions() is the primary accessor (getDefaultOptions() delegates to it)
        // and
        // is what ChatClient seeds every request's options from.
        return plainModel.getOptions();
    }

    /** Copy of the prompt with thinking cleared (tool turns; request options override defaults). */
    static Prompt withoutThinking(Prompt prompt) {
        if (prompt != null
                && prompt.getOptions() instanceof AnthropicChatOptions aco
                && aco.getThinking() != null) {
            AnthropicChatOptions copy = aco.mutate().thinking(null).build();
            return new Prompt(prompt.getInstructions(), copy);
        }
        return prompt;
    }

    /**
     * Copy of the prompt carrying the thinking model's thinking config, with temperature/top_p/
     * top_k cleared (Anthropic rejects them when thinking is on; the plain defaults the ChatClient
     * copied in would otherwise override the thinking model's null values).
     */
    private Prompt withThinking(Prompt prompt) {
        if (prompt != null
                && prompt.getOptions() instanceof AnthropicChatOptions aco
                && thinkingModel.getOptions() instanceof AnthropicChatOptions defaults
                && defaults.getThinking() != null) {
            AnthropicChatOptions.Builder copy =
                    aco.mutate()
                            .thinking(defaults.getThinking())
                            .temperature(null)
                            .topP(null)
                            .topK(null);
            if (aco.getMaxTokens() == null
                    || (defaults.getMaxTokens() != null
                            && aco.getMaxTokens() < defaults.getMaxTokens())) {
                copy.maxTokens(defaults.getMaxTokens());
            }
            return new Prompt(prompt.getInstructions(), copy.build());
        }
        return prompt;
    }

    private static void logToolTurn() {
        log.info(
                "Anthropic extended thinking skipped for this turn: request carries tools and "
                        + "tool loops run thinking-off (thinking replay across tool rounds is not relied on)");
    }

    /** True when the request offers any tool (attached tool callbacks), on any options type. */
    static boolean hasTools(Prompt prompt) {
        if (prompt != null && prompt.getOptions() instanceof ToolCallingChatOptions tco) {
            // Spring AI 2 dropped tool *names* from the options: the attached callbacks are the
            // only tools.
            return tco.getToolCallbacks() != null && !tco.getToolCallbacks().isEmpty();
        }
        return false;
    }

    static boolean isThinkingGeneration(Generation g) {
        if (g == null || g.getOutput() == null) {
            return false;
        }
        var props = g.getOutput().getMetadata();
        return props != null && (props.containsKey("signature") || props.containsKey("data"));
    }

    /**
     * Drops thinking generations. If nothing else remains (reply cut off while thinking, or a
     * streaming thinking/signature chunk) a single empty-text generation carrying the first
     * generation's metadata is kept so finish reason and usage still reach downstream readers.
     */
    static ChatResponse stripThinking(ChatResponse response) {
        if (response == null
                || response.getResults() == null
                || response.getResults().stream()
                        .noneMatch(AnthropicThinkingChatModel::isThinkingGeneration)) {
            return response;
        }
        List<Generation> kept = new ArrayList<>();
        for (Generation g : response.getResults()) {
            if (!isThinkingGeneration(g)) {
                kept.add(g);
            }
        }
        if (kept.isEmpty()) {
            Generation first = response.getResults().get(0);
            kept.add(
                    new Generation(
                            AssistantMessage.builder().content("").build(), first.getMetadata()));
        }
        return ChatResponse.builder().from(response).generations(kept).build();
    }
}
