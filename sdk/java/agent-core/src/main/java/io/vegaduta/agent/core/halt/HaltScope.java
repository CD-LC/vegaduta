package io.vegaduta.agent.core.halt;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a halt applies to. A halt is either global, or targets one tenant, one agent definition or
 * one run. The more specific the scope, the higher its {@link #specificity()}; {@link
 * HaltSwitch#isHalted(RunContext)} reports the most specific active match.
 *
 * @param kind the kind of scope
 * @param id the tenant, agent or run id; {@code null} only for {@link Kind#GLOBAL}
 */
@Experimental
public record HaltScope(Kind kind, @Nullable String id) {

  /** The kinds of scope, in increasing order of specificity. */
  public enum Kind {
    GLOBAL,
    TENANT,
    AGENT,
    RUN
  }

  private static final HaltScope GLOBAL = new HaltScope(Kind.GLOBAL, null);

  public HaltScope {
    Objects.requireNonNull(kind, "kind");
    if (kind == Kind.GLOBAL) {
      if (id != null) {
        throw new IllegalArgumentException("a GLOBAL halt scope carries no id");
      }
    } else if (id == null || id.isBlank()) {
      throw new IllegalArgumentException(kind + " halt scope needs an id");
    }
  }

  /** Every run of every agent of every tenant. */
  public static HaltScope global() {
    return GLOBAL;
  }

  /** Every run whose {@link RunContext#tenantId()} equals {@code tenantId}. */
  public static HaltScope tenant(String tenantId) {
    return new HaltScope(Kind.TENANT, tenantId);
  }

  /** Every run whose {@link RunContext#agentId()} equals {@code agentId}. */
  public static HaltScope agent(String agentId) {
    return new HaltScope(Kind.AGENT, agentId);
  }

  /** The single run whose {@link RunContext#runId()} equals {@code runId}. */
  public static HaltScope run(String runId) {
    return new HaltScope(Kind.RUN, runId);
  }

  /** Whether a call made in {@code context} falls under this scope. */
  public boolean matches(RunContext context) {
    Objects.requireNonNull(context, "context");
    switch (kind) {
      case GLOBAL:
        return true;
      case TENANT:
        return Objects.equals(id, context.tenantId());
      case AGENT:
        return Objects.equals(id, context.agentId());
      case RUN:
        return Objects.equals(id, context.runId());
      default:
        throw new IllegalStateException("unknown halt scope kind " + kind);
    }
  }

  /** Higher means narrower: {@code GLOBAL} is 0, {@code RUN} is 3. */
  public int specificity() {
    return kind.ordinal();
  }

  @Override
  public String toString() {
    return kind == Kind.GLOBAL ? "GLOBAL" : kind + "(" + id + ")";
  }
}
