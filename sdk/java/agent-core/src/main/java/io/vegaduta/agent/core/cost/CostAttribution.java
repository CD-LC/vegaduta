package io.vegaduta.agent.core.cost;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Well-known {@link RunContext} attribute keys that say who pays for a run. Tenant and agent are
 * first-class fields of the context; team, feature and cost centre travel as attributes so no
 * contract change is needed to attribute spend.
 *
 * <pre>{@code
 * RunContext ctx = CostAttribution.tag(RunContext.builder().tenantId("acme").build(),
 *     "payments", "refund-assistant", "CC-4711");
 * }</pre>
 */
@Experimental
public final class CostAttribution {

  /** The team that owns the agent. */
  public static final String TEAM = "cost.team";

  /** The product feature or use case the run serves. */
  public static final String FEATURE = "cost.feature";

  /** The accounting cost centre to charge. */
  public static final String COST_CENTER = "cost.costCenter";

  /** The label a report uses when a dimension is not set on a run. */
  public static final String UNATTRIBUTED = "(unattributed)";

  private CostAttribution() {}

  /** Returns a copy of {@code context} tagged with team, feature and cost centre. */
  public static RunContext tag(
      RunContext context,
      @Nullable String team,
      @Nullable String feature,
      @Nullable String costCenter) {
    Objects.requireNonNull(context, "context");
    RunContext.Builder b = context.toBuilder();
    if (team != null) {
      b.attribute(TEAM, team);
    }
    if (feature != null) {
      b.attribute(FEATURE, feature);
    }
    if (costCenter != null) {
      b.attribute(COST_CENTER, costCenter);
    }
    return b.build();
  }

  /** Returns a copy of {@code context} tagged with team and feature only. */
  public static RunContext tag(RunContext context, String team, String feature) {
    return tag(context, team, feature, null);
  }

  public static Optional<String> team(RunContext context) {
    return Optional.ofNullable(context.attributes().get(TEAM));
  }

  public static Optional<String> feature(RunContext context) {
    return Optional.ofNullable(context.attributes().get(FEATURE));
  }

  public static Optional<String> costCenter(RunContext context) {
    return Optional.ofNullable(context.attributes().get(COST_CENTER));
  }

  /** The team, or {@link #UNATTRIBUTED}. */
  public static String teamOrUnattributed(RunContext context) {
    return team(context).orElse(UNATTRIBUTED);
  }

  /** The feature, or {@link #UNATTRIBUTED}. */
  public static String featureOrUnattributed(RunContext context) {
    return feature(context).orElse(UNATTRIBUTED);
  }

  /** The cost centre, or {@link #UNATTRIBUTED}. */
  public static String costCenterOrUnattributed(RunContext context) {
    return costCenter(context).orElse(UNATTRIBUTED);
  }

  /** The tenant id, or {@link #UNATTRIBUTED}. */
  public static String tenantOrUnattributed(RunContext context) {
    return context.tenantId() == null ? UNATTRIBUTED : context.tenantId();
  }

  /** The agent id, or {@link #UNATTRIBUTED}. */
  public static String agentOrUnattributed(RunContext context) {
    return context.agentId() == null ? UNATTRIBUTED : context.agentId();
  }
}
