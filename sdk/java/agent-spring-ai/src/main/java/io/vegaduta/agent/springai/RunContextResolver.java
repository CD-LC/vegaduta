package io.vegaduta.agent.springai;

import io.vegaduta.agent.api.RunContext;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Finds the {@link RunContext} for a tool invocation. The default looks in the Spring AI {@link
 * ToolContext} under {@link #TOOL_CONTEXT_KEY}, then in {@link RunContextHolder}, and finally
 * creates an anonymous context (one run per call, which still gates and audits but cannot budget
 * across calls). Hosts replace it to derive tenant and principal from their own security context.
 */
@FunctionalInterface
public interface RunContextResolver {

  /**
   * Key under which {@link GovernanceAdvisor} puts the {@link RunContext} into the tool context.
   */
  String TOOL_CONTEXT_KEY = "vegaduta.runContext";

  RunContext resolve(@Nullable ToolContext toolContext);

  static RunContextResolver defaults() {
    return toolContext -> {
      if (toolContext != null) {
        Object value = toolContext.getContext().get(TOOL_CONTEXT_KEY);
        if (value instanceof RunContext context) {
          return context;
        }
      }
      return RunContextHolder.current().orElseGet(RunContext::anonymous);
    };
  }
}
