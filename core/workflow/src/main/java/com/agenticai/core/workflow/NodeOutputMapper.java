package com.agenticai.core.workflow;

import com.agenticai.core.agents.turn.AgentChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Maps a structured agent output to the single string that flows to the next node. */
@Component
class NodeOutputMapper {

    private final ObjectMapper objectMapper;

    NodeOutputMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String applyOutputMapping(String outputField, AgentChatService.StructuredAgentOutput output) {
        return switch (outputField) {
            case "confidence" -> String.valueOf(output.confidence());
            case "sources" -> writeJsonQuietly(output.sources());
            case "tool_calls" -> writeJsonQuietly(output.toolCalls());
            case "metadata" -> writeJsonQuietly(output.metadata());
            default -> output.resultText() == null ? "" : output.resultText();
        };
    }

    String writeJsonQuietly(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
