package io.vegaduta.agent.core.memory;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.guardrail.GuardrailResult;
import io.vegaduta.agent.api.guardrail.GuardrailStage;
import io.vegaduta.agent.api.hook.FailMode;
import io.vegaduta.agent.api.hook.Hook;
import io.vegaduta.agent.api.hook.HookDecision;
import io.vegaduta.agent.api.hook.HookEvent;
import io.vegaduta.agent.api.hook.HookPoint;
import io.vegaduta.agent.core.guardrail.BuiltInRules;
import io.vegaduta.agent.core.guardrail.PatternGuardrail;
import io.vegaduta.agent.core.guardrail.PatternRule;
import io.vegaduta.agent.core.hook.HookRegistration;
import io.vegaduta.agent.core.hook.JsonLite;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Blocks calls to memory-writing tools whose arguments carry prompt-injection phrases.
 *
 * <p>A memory tool ({@code remember}, {@code save_note}, {@code update_profile}) is the path by
 * which a hijacked turn makes its damage permanent. This hook runs at {@link
 * HookPoint#BEFORE_TOOL_CALL} for a registered set of tool names, scans {@link HookEvent#payload()}
 * (the arguments JSON) with the built-in injection rules and returns {@link HookDecision.Block} on
 * a match. The raw JSON text is scanned, and so is every decoded top-level string value, so a
 * phrase split by a JSON escape ({@code ignore previous\\ninstructions}) is still caught. Nested
 * values are only covered by the raw scan. Everything else, including other tools and other hook
 * points, continues.
 *
 * <p>Register it with {@link #registration()}, which uses {@link FailMode#CLOSED}: if the hook
 * itself fails, the memory write is blocked rather than let through unchecked.
 */
@Experimental
public final class MemoryToolHook implements Hook {

  public static final String NAME = "memory-tool-injection";

  private final Set<String> toolNames;
  private final PatternGuardrail rules;

  /** Guards {@code toolNames} with {@link BuiltInRules#promptInjection()}. */
  public MemoryToolHook(Set<String> toolNames) {
    this(toolNames, BuiltInRules.promptInjection());
  }

  /** Guards {@code toolNames} with the given rules (matched regardless of their stages). */
  public MemoryToolHook(Set<String> toolNames, List<PatternRule> injectionRules) {
    Objects.requireNonNull(toolNames, "toolNames");
    if (toolNames.isEmpty()) {
      throw new IllegalArgumentException("toolNames must not be empty");
    }
    this.toolNames = Set.copyOf(toolNames);
    this.rules = new PatternGuardrail(NAME, injectionRules);
  }

  @Override
  public HookDecision apply(HookEvent event) {
    if (event.point() != HookPoint.BEFORE_TOOL_CALL
        || event.toolName() == null
        || !toolNames.contains(event.toolName())) {
      return HookDecision.CONTINUE;
    }
    GuardrailResult result = evaluateAllStages(event);
    if (result.clean()) {
      return HookDecision.CONTINUE;
    }
    return HookDecision.block(
        "memory_injection:" + MemoryWriteBlockedException.codes(result.violations()));
  }

  /** The tool names this hook guards. */
  public Set<String> toolNames() {
    return toolNames;
  }

  /** A fail-closed registration at {@link HookPoint#BEFORE_TOOL_CALL} for every tool. */
  public HookRegistration registration() {
    return new HookRegistration(
        NAME, Set.of(HookPoint.BEFORE_TOOL_CALL), "*", this, FailMode.CLOSED);
  }

  private GuardrailResult evaluateAllStages(HookEvent event) {
    GuardrailResult r = evaluate(event.payload(), event);
    if (!r.clean()) {
      return r;
    }
    // Decoded values: JSON escaping must not split an injection phrase.
    for (String value : JsonLite.readFlatObject(event.payload()).values()) {
      r = evaluate(value, event);
      if (!r.clean()) {
        return r;
      }
    }
    return r;
  }

  private GuardrailResult evaluate(String text, HookEvent event) {
    // The built-in injection rules are scoped to TOOL_OUTPUT; a memory write is semantically the
    // same hazard (untrusted text about to become trusted), so evaluate at that stage and fall
    // back to TOOL_ARGUMENTS for custom rules scoped there.
    GuardrailResult r = rules.evaluate(text, GuardrailStage.TOOL_OUTPUT, event.context());
    if (!r.clean()) {
      return r;
    }
    return rules.evaluate(text, GuardrailStage.TOOL_ARGUMENTS, event.context());
  }
}
