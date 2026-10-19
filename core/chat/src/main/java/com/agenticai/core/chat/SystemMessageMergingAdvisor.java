package com.agenticai.core.chat;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Collapses every {@link SystemMessage} in a request's prompt into a single one
 * before the call reaches the model. Exists for exactly one reason: Spring AI's
 * {@code GoogleGenAiChatModel} (the Vertex AI + Gemini AI-Studio path) rejects a
 * prompt carrying more than one system message with {@code
 * IllegalArgumentException: "Only one system message is allowed"} - Gemini's API
 * takes a single {@code systemInstruction}, not a list. Every OpenAI-compatible
 * provider in this codebase tolerates multiple system messages, so this was
 * invisible until the platform-hosted pool gained a Vertex row (V169): {@code
 * AgentChatService} routinely emits two or three system messages per turn (the
 * agent's base instructions, a recalled-memory block, and - in planned mode - a
 * plan block; see that class's {@code prependPlanBlock} and the memory-block
 * assembly), which made every FREE-tier chat turn on a Vertex hosted provider
 * fail with the above.
 *
 * <p><b>Why this is safe to apply broadly.</b> When a prompt has zero or one
 * system message - i.e. every well-formed request an OpenAI-style provider ever
 * saw - {@link #mergeSystemMessages} returns the request instance UNCHANGED, so
 * layering this onto a client is a strict no-op except in exactly the case that
 * would otherwise throw. When it does merge, it concatenates the system texts in
 * their original order (separated by a blank line), places the single merged
 * message at the position of the FIRST system message, and leaves every
 * non-system message (user/assistant/tool) exactly where it was - so the model
 * sees the same instruction content in the same order, just as one block.
 *
 * <p>Only wired onto the {@code GoogleGenAiChatModel}-backed clients ({@link
 * VertexAiChatClientFactory}, {@link GeminiAgentChatClientFactory}, and {@code
 * HostedLlmChatClientFactory}'s Vertex branch) - the OpenAI-compat factories are
 * deliberately left untouched, since they never had the problem and there is no
 * reason to alter a request shape that already works for them.
 */
public class SystemMessageMergingAdvisor implements CallAdvisor, StreamAdvisor {

    /** Blank line between merged system blocks, so they read as distinct paragraphs rather than running together. */
    static final String SEPARATOR = "\n\n";

    @Override
    public String getName() {
        return "SystemMessageMergingAdvisor";
    }

    @Override
    public int getOrder() {
        // Run BEFORE the request-shaping is observed by anything else in the
        // chain (e.g. LlmMetricsAdvisor's request logging at order 0), so what
        // is logged/sent is the merged shape. Any negative order achieves this;
        // correctness of the model call does not actually depend on it, since
        // every advisor forwards the (rewritten) request down the chain anyway.
        return -1000;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(mergeSystemMessages(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(mergeSystemMessages(request));
    }

    /**
     * Returns {@code request} unchanged when it has 0 or 1 system messages;
     * otherwise a copy whose system messages are merged into one. Package-private
     * as a test seam.
     */
    static ChatClientRequest mergeSystemMessages(ChatClientRequest request) {
        Prompt prompt = request.prompt();
        List<Message> messages = prompt.getInstructions();

        long systemCount = messages.stream().filter(SystemMessage.class::isInstance).count();
        if (systemCount <= 1) {
            return request;
        }

        List<String> systemTexts = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof SystemMessage system) {
                String text = system.getText();
                if (text != null && !text.isBlank()) {
                    systemTexts.add(text);
                }
            }
        }
        SystemMessage merged = new SystemMessage(String.join(SEPARATOR, systemTexts));

        List<Message> rewritten = new ArrayList<>(messages.size());
        boolean placedMerged = false;
        for (Message message : messages) {
            if (message instanceof SystemMessage) {
                if (!placedMerged) {
                    rewritten.add(merged);
                    placedMerged = true;
                }
                // Later system messages are folded into `merged`, so drop them.
            } else {
                rewritten.add(message);
            }
        }

        Prompt rewrittenPrompt = new Prompt(rewritten, prompt.getOptions());
        return request.mutate().prompt(rewrittenPrompt).build();
    }
}
