package com.agenticai.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * Static helpers extracted from {@link ArgumentSanitizingToolCallingManager} (file-size ratchet).
 */
final class ToolCallSanitizeHelpers {

    private static final Logger log = LoggerFactory.getLogger(ToolCallSanitizeHelpers.class);
    private static final String EMPTY_ARGUMENTS = "{}";

    private ToolCallSanitizeHelpers() {}

    static Set<String> knownToolNames(Prompt prompt) {
        Set<String> known = new LinkedHashSet<>();
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
            return known;
        }
        if (options.getToolNames() != null) {
            known.addAll(options.getToolNames());
        }
        if (options.getToolCallbacks() != null) {
            for (ToolCallback callback : options.getToolCallbacks()) {
                known.add(callback.getToolDefinition().name());
            }
        }
        return known;
    }

    static List<AssistantMessage.ToolCall> requestedToolCalls(ChatResponse chatResponse) {
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        if (chatResponse == null || chatResponse.getResults() == null) {
            return toolCalls;
        }
        for (Generation generation : chatResponse.getResults()) {
            AssistantMessage output = generation == null ? null : generation.getOutput();
            if (output != null && output.hasToolCalls()) {
                toolCalls.addAll(output.getToolCalls());
            }
        }
        return toolCalls;
    }

    static AssistantMessage sanitizeAssistantMessage(
            AssistantMessage assistantMessage, ObjectMapper objectMapper) {
        List<AssistantMessage.ToolCall> repaired =
                new ArrayList<>(assistantMessage.getToolCalls().size());
        boolean changed = false;
        for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
            if (isValidJson(toolCall.arguments(), objectMapper)) {
                repaired.add(toolCall);
                continue;
            }
            changed = true;
            log.warn(
                    "Repairing non-JSON tool-call arguments before replay: tool={} argumentsPreview={}",
                    toolCall.name(),
                    preview(toolCall.arguments()));
            repaired.add(
                    new AssistantMessage.ToolCall(
                            toolCall.id(), toolCall.type(), toolCall.name(), EMPTY_ARGUMENTS));
        }
        if (!changed) {
            return assistantMessage;
        }
        return AssistantMessage.builder()
                .content(assistantMessage.getText())
                .properties(assistantMessage.getMetadata())
                .toolCalls(repaired)
                .media(assistantMessage.getMedia())
                .build();
    }

    static boolean isValidJson(String arguments, ObjectMapper objectMapper) {
        if (arguments == null || arguments.isBlank()) {
            return false;
        }
        try {
            objectMapper.readTree(arguments);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String preview(String arguments) {
        if (arguments == null) {
            return "null";
        }
        return arguments.length() <= 120 ? arguments : arguments.substring(0, 120) + "...";
    }
}
