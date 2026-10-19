package io.vegaduta.agent.core.hook;

import io.vegaduta.agent.api.hook.FailMode;
import io.vegaduta.agent.api.hook.HookDecision;
import io.vegaduta.agent.api.hook.HookEvent;
import io.vegaduta.agent.api.hook.HookPoint;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs registered hooks in registration order. A {@code Block} stops the chain; a {@code Replace}
 * changes the payload the next hook (and the pipeline) sees.
 */
public final class HookEngine {

  private static final Logger log = LoggerFactory.getLogger(HookEngine.class);

  private final List<HookRegistration> registrations = new CopyOnWriteArrayList<>();

  public HookEngine register(HookRegistration registration) {
    registrations.add(registration);
    return this;
  }

  public HookEngine unregister(String name) {
    registrations.removeIf(r -> r.name().equals(name));
    return this;
  }

  public boolean isEmpty() {
    return registrations.isEmpty();
  }

  public List<HookRegistration> registrations() {
    return List.copyOf(registrations);
  }

  /** Runs the hooks for the event's point and returns the combined decision. */
  public HookDecision run(HookEvent event) {
    HookEvent current = event;
    boolean replaced = false;
    for (HookRegistration registration : registrations) {
      if (!registration.matches(event.point(), toolNameOrEmpty(event))) {
        continue;
      }
      HookDecision decision;
      try {
        decision = registration.hook().apply(current);
      } catch (RuntimeException e) {
        if (registration.failMode() == FailMode.CLOSED) {
          log.warn(
              "Hook '{}' failed closed at {}: {}",
              registration.name(),
              event.point(),
              e.toString());
          return HookDecision.block("hook_failed:" + registration.name());
        }
        log.warn(
            "Hook '{}' failed open at {}: {}", registration.name(), event.point(), e.toString());
        continue;
      }
      if (decision instanceof HookDecision.Block) {
        return decision;
      }
      if (decision instanceof HookDecision.Replace replace) {
        current =
            new HookEvent(
                current.point(),
                current.context(),
                current.toolName(),
                replace.payload(),
                current.attributes());
        replaced = true;
      }
    }
    return replaced ? HookDecision.replace(current.payload()) : HookDecision.CONTINUE;
  }

  /** Runs the hooks for a notification-only point; decisions are ignored. */
  public void notify(HookEvent event) {
    run(event);
  }

  /** The hook points at which at least one hook is registered. */
  public boolean hasHooksFor(HookPoint point) {
    return registrations.stream().anyMatch(r -> r.points().contains(point));
  }

  private static String toolNameOrEmpty(HookEvent event) {
    return event.toolName() == null ? "" : event.toolName();
  }
}
