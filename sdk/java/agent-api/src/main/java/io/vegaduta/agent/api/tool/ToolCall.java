package io.vegaduta.agent.api.tool;

import java.util.Objects;
import java.util.UUID;

/**
 * One invocation request for a tool.
 *
 * @param callId unique id for this call (the model's tool-call id when the framework gives one)
 * @param toolName the tool's name
 * @param argumentsJson the arguments as a JSON object string
 */
public record ToolCall(String callId, String toolName, String argumentsJson) {

  public ToolCall {
    Objects.requireNonNull(callId, "callId");
    Objects.requireNonNull(toolName, "toolName");
    Objects.requireNonNull(argumentsJson, "argumentsJson");
  }

  public static ToolCall of(String toolName, String argumentsJson) {
    return new ToolCall(UUID.randomUUID().toString(), toolName, argumentsJson);
  }
}
