package io.vegaduta.agent.api.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What the model sees about a tool.
 *
 * @param name the tool name as offered to the model; unique within one agent
 * @param description natural-language description
 * @param inputSchemaJson JSON Schema for the arguments object, or {@code "{}"}
 * @param metadata adapter-specific extras (for example the MCP server the tool came from)
 */
public record ToolDescriptor(
    String name, String description, String inputSchemaJson, Map<String, String> metadata) {

  public ToolDescriptor {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(inputSchemaJson, "inputSchemaJson");
    if (name.isBlank()) {
      throw new IllegalArgumentException("tool name must not be blank");
    }
    metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
  }

  public static ToolDescriptor of(String name, String description) {
    return new ToolDescriptor(name, description, "{}", Map.of());
  }

  public static ToolDescriptor of(String name, String description, String inputSchemaJson) {
    return new ToolDescriptor(name, description, inputSchemaJson, Map.of());
  }

  public ToolDescriptor withMetadata(String key, String value) {
    Map<String, String> copy = new LinkedHashMap<>(metadata);
    copy.put(key, value);
    return new ToolDescriptor(name, description, inputSchemaJson, copy);
  }
}
