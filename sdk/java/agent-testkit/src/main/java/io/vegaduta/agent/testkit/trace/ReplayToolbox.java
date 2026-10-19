package io.vegaduta.agent.testkit.trace;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.tool.Tool;
import io.vegaduta.agent.api.tool.ToolDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds one {@link ReplayTool} per tool name found in a trace, ready to be governed and offered to
 * the agent under test in place of the real tools.
 *
 * <pre>{@code
 * Trace trace = Trace.load(Path.of("src/test/resources/traces/refund-happy-path.jsonl"));
 * List<Tool> tools = ReplayToolbox.fromTrace(trace);
 * agent.run(governance.governAll(tools));
 * }</pre>
 */
@Experimental
public final class ReplayToolbox {

  private ReplayToolbox() {}

  /** Strict replay tools for every tool name in the trace, in first-seen order. */
  public static List<Tool> fromTrace(Trace trace) {
    return fromTrace(trace, ReplayMode.STRICT);
  }

  public static List<Tool> fromTrace(Trace trace, ReplayMode mode) {
    return fromTrace(trace, mode, Map.of());
  }

  /**
   * Replay tools using the given descriptors where available (so the model sees the real schema)
   * and a generated descriptor otherwise.
   */
  public static List<Tool> fromTrace(
      Trace trace, ReplayMode mode, Map<String, ToolDescriptor> descriptors) {
    Objects.requireNonNull(trace, "trace");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(descriptors, "descriptors");
    List<Tool> tools = new ArrayList<>();
    for (String name : trace.toolNames()) {
      ToolDescriptor descriptor = descriptors.get(name);
      tools.add(
          descriptor == null
              ? ReplayTool.of(trace, name, mode)
              : ReplayTool.of(trace, descriptor, mode));
    }
    return List.copyOf(tools);
  }

  /**
   * Replay tools that keep the descriptors of the given real tools, in the real tools' order. A
   * real tool the trace never called still gets a replay tool (with no entries), so a call to it on
   * the replay leg is a {@link ReplayMismatchException} with a diff rather than a framework-level
   * unknown tool; tool names found only in the trace are appended after the real ones.
   */
  public static List<Tool> fromTrace(Trace trace, ReplayMode mode, List<? extends Tool> real) {
    Objects.requireNonNull(trace, "trace");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(real, "real");
    List<Tool> tools = new ArrayList<>();
    Map<String, ToolDescriptor> seen = new LinkedHashMap<>();
    for (Tool t : real) {
      if (seen.putIfAbsent(t.name(), t.descriptor()) == null) {
        tools.add(ReplayTool.of(trace, t.descriptor(), mode));
      }
    }
    for (String name : trace.toolNames()) {
      if (!seen.containsKey(name)) {
        tools.add(ReplayTool.of(trace, name, mode));
      }
    }
    return List.copyOf(tools);
  }
}
