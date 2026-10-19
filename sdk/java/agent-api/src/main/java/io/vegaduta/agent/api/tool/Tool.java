package io.vegaduta.agent.api.tool;

import io.vegaduta.agent.api.RunContext;

/**
 * A framework-neutral tool: a name, a description, an input schema and a function from a JSON
 * argument string to a result string.
 *
 * <p>Framework adapters wrap their native tool type (Spring AI {@code ToolCallback}, LangChain4j
 * {@code ToolExecutor}) in this interface, and wrap the governed {@code Tool} back into the native
 * type. The governance pipeline only ever sees this interface.
 */
public interface Tool {

  ToolDescriptor descriptor();

  /**
   * Executes the tool.
   *
   * @param argumentsJson the model's arguments as a JSON object string (may be {@code "{}"})
   * @param context the run the call belongs to
   * @return the tool's output; a string the model will read
   */
  String call(String argumentsJson, RunContext context);

  /** Convenience for {@code descriptor().name()}. */
  default String name() {
    return descriptor().name();
  }
}
