package io.vegaduta.agent.springai;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.tool.Tool;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.api.tool.ToolDescriptor;
import io.vegaduta.agent.api.tool.ToolResult;
import io.vegaduta.agent.core.Governance;
import io.vegaduta.agent.core.GovernedTool;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * A Spring AI {@link ToolCallback} whose calls go through the governance pipeline. The model sees
 * the delegate's definition unchanged; what it gets back is either the delegate's output or a
 * governance explanation.
 */
public final class GovernedToolCallback implements ToolCallback {

  private final ToolCallback delegate;
  private final GovernedTool governed;
  private final RunContextResolver contextResolver;
  private final ThreadLocal<@Nullable ToolContext> currentToolContext = new ThreadLocal<>();

  GovernedToolCallback(ToolCallback delegate, Governance governance, RunContextResolver resolver) {
    this.delegate = delegate;
    this.contextResolver = resolver;
    this.governed = governance.govern(new DelegateTool());
  }

  public ToolCallback delegate() {
    return delegate;
  }

  public GovernedTool governed() {
    return governed;
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  @Override
  public ToolMetadata getToolMetadata() {
    return delegate.getToolMetadata();
  }

  @Override
  public String call(String toolInput) {
    return call(toolInput, null);
  }

  @Override
  public String call(String toolInput, @Nullable ToolContext toolContext) {
    RunContext context = contextResolver.resolve(toolContext);
    ToolContext previous = currentToolContext.get();
    currentToolContext.set(toolContext);
    try {
      ToolResult result =
          governed.invoke(ToolCall.of(delegate.getToolDefinition().name(), toolInput), context);
      return result.output();
    } finally {
      if (previous == null) {
        currentToolContext.remove();
      } else {
        currentToolContext.set(previous);
      }
    }
  }

  /** The framework-neutral view of the delegate; passes the Spring AI tool context through. */
  private final class DelegateTool implements Tool {

    @Override
    public ToolDescriptor descriptor() {
      ToolDefinition def = delegate.getToolDefinition();
      return new ToolDescriptor(
          def.name(),
          def.description(),
          def.inputSchema(),
          Map.of(
              "framework",
              "spring-ai",
              "returnDirect",
              String.valueOf(delegate.getToolMetadata().returnDirect())));
    }

    @Override
    public String call(String argumentsJson, RunContext context) {
      ToolContext toolContext = currentToolContext.get();
      String out =
          toolContext == null
              ? delegate.call(argumentsJson)
              : delegate.call(argumentsJson, toolContext);
      return out == null ? "" : out;
    }
  }
}
