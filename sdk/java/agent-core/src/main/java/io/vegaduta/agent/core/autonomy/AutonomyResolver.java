package io.vegaduta.agent.core.autonomy;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.autonomy.AutonomyLevel;
import io.vegaduta.agent.core.text.Text;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the autonomy level a run operates at. In order:
 *
 * <ol>
 *   <li>the level registered for {@link RunContext#agentId()}, when the registry has one
 *   <li>the run attribute {@value #ATTRIBUTE}, when present and a valid {@link AutonomyLevel} name
 *       (a host can pin a level per run, e.g. from a feature flag)
 *   <li>the registry's default level
 * </ol>
 *
 * <p>The registry wins over the attribute on purpose: a demotion issued by an operator must not be
 * undone by a run that still carries the old attribute.
 *
 * <p>Fails closed: an attribute that is present but not a level name (a typo in a restriction such
 * as {@code "L1-OBSERVE"}) resolves to {@link AutonomyLevel#L1_OBSERVE}, the least permissive
 * level, and is audited as {@value AutonomyRegistry#INVALID_ATTRIBUTE_ACTION_TYPE}. Falling back to
 * the default would grant more autonomy than the attribute meant to allow.
 */
@Experimental
public final class AutonomyResolver {

  /** Run-context attribute carrying a level name, e.g. {@code "L2_RECOMMEND"}. */
  public static final String ATTRIBUTE = "vegaduta.autonomy";

  private static final Logger log = LoggerFactory.getLogger(AutonomyResolver.class);

  private final AutonomyRegistry registry;

  public AutonomyResolver(AutonomyRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  public AutonomyRegistry registry() {
    return registry;
  }

  /** The level for this run. Never throws; a malformed attribute resolves to the lowest level. */
  public AutonomyLevel resolve(RunContext context) {
    String agentId = context.agentId();
    if (agentId != null) {
      AutonomyLevel registered = registry.get(agentId).orElse(null);
      if (registered != null) {
        return registered;
      }
    }
    String attribute = context.attributes().get(ATTRIBUTE);
    if (attribute != null) {
      try {
        return AutonomyLevel.parse(attribute);
      } catch (IllegalArgumentException e) {
        log.warn(
            "autonomy: run {} carries an invalid {} attribute ('{}'); resolving to {}",
            context.runId(),
            ATTRIBUTE,
            Text.bounded(attribute, 64),
            AutonomyLevel.L1_OBSERVE);
        registry.auditInvalidAttribute(context, attribute);
        return AutonomyLevel.L1_OBSERVE;
      }
    }
    return registry.defaultLevel();
  }
}
