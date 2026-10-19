package com.agenticai.core.chat;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/**
 * Makes Anthropic extended thinking safe on Spring AI 1.1.7. Only built when a thinking budget
 * is configured; with thinking off the factory returns the plain model untouched.
 *
 * <p>Two upstream defects are handled here:
 * <ol>
 *   <li><b>Thinking is the first generation.</b> 1.1.7 maps every content block to its own
 *       {@link Generation}, thinking first, and {@code ChatResponse#getResult()} (hence
 *       {@code ChatClient...content()}) reads generation 0, so the reply became the reasoning
 *       text. {@link #stripThinking} removes thinking generations (marked by a {@code signature}
 *       property, or {@code data} for redacted thinking) so the final text is the result.</li>
 *   <li><b>Thinking blocks are not replayed in tool loops.</b> The SDK rebuilds the assistant
 *       turn from text and tool_use only, so a thinking-enabled request that continues after a
 *       tool call is rejected by Anthropic (400). A request that carries tools is therefore
 *       routed to {@code plainModel} (thinking off, normal sampling) with a log line.
 *       Interleaved thinking with tools would need the signed thinking blocks of each assistant
 *       turn replayed byte-for-byte before the tool_result; 1.1.7 offers no seam for that (the
 *       ToolCallingManager keeps only the tool-call generation's message, and createRequest only
 *       emits text + tool_use blocks), so this is a documented limitation, not a flag.</li>
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
        return thinkingModel.stream(withThinking(prompt)).map(AnthropicThinkingChatModel::stripThinking);
    }

    /**
     * ChatClient copies {@code getDefaultOptions()} into every request and Spring AI merges the
     * request options OVER the model's own defaults, so whatever this returns wins. It must be
     * the thinking-OFF options: otherwise thinking=ENABLED would ride along on tool turns and
     * defeat the routing. Tool-free turns re-apply thinking explicitly in {@link #withThinking}.
     */
    @Override
    public ChatOptions getDefaultOptions() {
        return plainModel.getDefaultOptions();
    }

    /** Copy of the prompt with thinking cleared (tool turns; request options override defaults). */
    static Prompt withoutThinking(Prompt prompt) {
        if (prompt != null && prompt.getOptions() instanceof AnthropicChatOptions aco && aco.getThinking() != null) {
            AnthropicChatOptions copy = aco.copy();
            copy.setThinking(null);
            return new Prompt(prompt.getInstructions(), copy);
        }
        return prompt;
    }

    /**
     * Copy of the prompt carrying the thinking model's thinking config, with temperature/top_p/
     * top_k cleared (Anthropic rejects them when thinking is on; the plain defaults the
     * ChatClient copied in would otherwise override the thinking model's null values).
     */
    private Prompt withThinking(Prompt prompt) {
        if (prompt != null
            && prompt.getOptions() instanceof AnthropicChatOptions aco
            && thinkingModel.getDefaultOptions() instanceof AnthropicChatOptions defaults
            && defaults.getThinking() != null) {
            AnthropicChatOptions copy = aco.copy();
            copy.setThinking(defaults.getThinking());
            copy.setTemperature(null);
            copy.setTopP(null);
            copy.setTopK(null);
            if (copy.getMaxTokens() == null || (defaults.getMaxTokens() != null
                && copy.getMaxTokens() < defaults.getMaxTokens())) {
                copy.setMaxTokens(defaults.getMaxTokens());
            }
            return new Prompt(prompt.getInstructions(), copy);
        }
        return prompt;
    }

    private static void logToolTurn() {
        log.info("Anthropic extended thinking skipped for this turn: request carries tools and "
            + "Spring AI 1.1.7 does not replay thinking blocks in tool loops");
    }

    /** True when the request offers any tool (callbacks or tool names), on any options type. */
    static boolean hasTools(Prompt prompt) {
        if (prompt != null && prompt.getOptions() instanceof ToolCallingChatOptions tco) {
            return (tco.getToolCallbacks() != null && !tco.getToolCallbacks().isEmpty())
                || (tco.getToolNames() != null && !tco.getToolNames().isEmpty());
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
        if (response == null || response.getResults() == null
            || response.getResults().stream().noneMatch(AnthropicThinkingChatModel::isThinkingGeneration)) {
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
            kept.add(new Generation(AssistantMessage.builder().content("").build(), first.getMetadata()));
        }
        return ChatResponse.builder().from(response).generations(kept).build();
    }
}
