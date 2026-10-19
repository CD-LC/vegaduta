package com.agenticai.core.agents.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * The subset of an OpenAI {@code POST /v1/chat/completions} body this platform
 * honours. Unknown fields ({@code temperature}, {@code tools}, {@code
 * max_tokens}, ...) are accepted and ignored so an unmodified OpenAI SDK never
 * gets a 400 for a field the agent owns anyway: the agent's own system prompt,
 * model, sampling and server-side tools run exactly as on the chat endpoint.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiChatRequest(
    String model,
    List<Message> messages,
    Boolean stream,
    @JsonProperty("stream_options") StreamOptions streamOptions
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, JsonNode content, String name) {
        /** String content, or every {@code text} part of a parts array joined by newlines; never null. */
        public String text() {
            if (content == null || content.isNull()) {
                return "";
            }
            if (content.isTextual()) {
                return content.asText();
            }
            if (content.isArray()) {
                StringBuilder joined = new StringBuilder();
                for (JsonNode part : content) {
                    JsonNode type = part.get("type");
                    JsonNode text = part.get("text");
                    if (type != null && "text".equals(type.asText()) && text != null && text.isTextual()) {
                        if (joined.length() > 0) {
                            joined.append('\n');
                        }
                        joined.append(text.asText());
                    }
                }
                return joined.toString();
            }
            return "";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StreamOptions(@JsonProperty("include_usage") Boolean includeUsage) {}

    public boolean isStream() {
        return Boolean.TRUE.equals(stream);
    }

    public boolean includeUsage() {
        return streamOptions != null && Boolean.TRUE.equals(streamOptions.includeUsage());
    }

    /**
     * Folds the OpenAI message list into the single user turn the agent chat
     * service takes.
     *
     * @param serverHoldsHistory true when the caller supplied a session id, so
     *     the platform already holds earlier turns and only the last user
     *     message is sent (replaying the client's copy would double every turn,
     *     the same posture as the AG-UI endpoint). When false the call is
     *     stateless, as OpenAI clients expect, so earlier turns are rendered
     *     into the prompt as a transcript.
     * @throws IllegalArgumentException when there is no user message with text
     */
    public String toPrompt(boolean serverHoldsHistory) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("'messages' is required and must contain at least one message");
        }
        int lastUser = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m != null && "user".equals(m.role()) && !m.text().isBlank()) {
                lastUser = i;
                break;
            }
        }
        if (lastUser < 0) {
            throw new IllegalArgumentException("'messages' must contain at least one user message with text content");
        }
        String finalText = messages.get(lastUser).text();
        if (serverHoldsHistory) {
            return finalText;
        }
        List<String> system = new ArrayList<>();
        List<String> transcript = new ArrayList<>();
        for (int i = 0; i < lastUser; i++) {
            Message m = messages.get(i);
            if (m == null || m.role() == null || m.text().isBlank()) {
                continue;
            }
            switch (m.role()) {
                case "system", "developer" -> system.add(m.text());
                case "user" -> transcript.add("User: " + m.text());
                case "assistant" -> transcript.add("Assistant: " + m.text());
                case "tool" -> transcript.add("Tool result: " + m.text());
                default -> { }
            }
        }
        if (system.isEmpty() && transcript.isEmpty()) {
            return finalText;
        }
        StringBuilder prompt = new StringBuilder();
        if (!system.isEmpty()) {
            prompt.append("Instructions from the caller:\n").append(String.join("\n", system)).append("\n\n");
        }
        if (!transcript.isEmpty()) {
            prompt.append("Conversation so far:\n").append(String.join("\n", transcript)).append("\n\n");
            prompt.append("Latest user message:\n");
        }
        return prompt.append(finalText).toString();
    }
}
