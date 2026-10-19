package io.vegaduta.agent.testkit;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.tool.Tool;
import io.vegaduta.agent.api.tool.ToolDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** A tool that records its calls and answers with a function of the arguments. */
public final class FakeTool implements Tool {

  private final ToolDescriptor descriptor;
  private final Function<String, String> behaviour;
  private final List<String> calls = new ArrayList<>();

  private FakeTool(String name, Function<String, String> behaviour) {
    this.descriptor = ToolDescriptor.of(name, "fake tool " + name);
    this.behaviour = behaviour;
  }

  public static FakeTool echo(String name) {
    return new FakeTool(name, args -> args);
  }

  public static FakeTool returning(String name, String output) {
    return new FakeTool(name, args -> output);
  }

  public static FakeTool of(String name, Function<String, String> behaviour) {
    return new FakeTool(name, behaviour);
  }

  public static FakeTool failing(String name, RuntimeException error) {
    return new FakeTool(
        name,
        args -> {
          throw error;
        });
  }

  @Override
  public ToolDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public synchronized String call(String argumentsJson, RunContext context) {
    calls.add(argumentsJson);
    return behaviour.apply(argumentsJson);
  }

  /** The arguments of every call so far. */
  public synchronized List<String> calls() {
    return List.copyOf(calls);
  }

  public int callCount() {
    return calls().size();
  }
}
