package io.vegaduta.agent.api.gate;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.tool.ToolCall;

/**
 * Decides, before a tool runs, whether it may run now, must wait for a human, or must not run.
 *
 * <p>Implementations are expected to be fast and deterministic: they run on every tool call of
 * every turn. Content inspection belongs in a {@link
 * io.vegaduta.agent.api.guardrail.ContentGuardrail}.
 */
@FunctionalInterface
public interface ToolDispatchGate {

  Verdict check(ToolCall call, RunContext context);

  /** A gate that allows everything. */
  static ToolDispatchGate allowAll() {
    return (call, context) -> Verdict.allow();
  }
}
