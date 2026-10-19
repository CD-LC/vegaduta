package io.vegaduta.agent.core.hook;

import io.vegaduta.agent.api.hook.FailMode;
import io.vegaduta.agent.api.hook.Hook;
import io.vegaduta.agent.api.hook.HookPoint;
import java.util.Objects;
import java.util.Set;

/**
 * A hook plus where it runs.
 *
 * @param name for logs and audit
 * @param points execution points the hook runs at
 * @param toolPattern glob over the tool name ({@code *} matches anything); ignored for model points
 * @param hook the hook
 * @param failMode what to do when the hook throws
 */
public record HookRegistration(
    String name, Set<HookPoint> points, String toolPattern, Hook hook, FailMode failMode) {

  public HookRegistration {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(toolPattern, "toolPattern");
    Objects.requireNonNull(hook, "hook");
    Objects.requireNonNull(failMode, "failMode");
    points = Set.copyOf(points);
  }

  public static HookRegistration of(String name, HookPoint point, Hook hook) {
    return new HookRegistration(name, Set.of(point), "*", hook, FailMode.OPEN);
  }

  public boolean matches(HookPoint point, String toolName) {
    return points.contains(point) && Globs.matches(toolPattern, toolName);
  }
}
