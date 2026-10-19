package io.vegaduta.agent.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Who is running what, on behalf of whom. Travels with every tool call so that gates, approvals,
 * budgets, journals and audit events can be scoped without any framework-specific thread-local.
 *
 * <p>Only {@code runId} is mandatory. Everything else is optional so the SDK works for a single
 * process with no tenancy as well as for a multi-tenant platform.
 *
 * @param tenantId tenant or workspace identifier, or {@code null} for single-tenant use
 * @param principal the user or service account the agent acts for, or {@code null}
 * @param agentId the agent definition being run, or {@code null}
 * @param sessionId the conversation or session, or {@code null}
 * @param runId one agent run (a turn, a job, a workflow step); groups all its tool calls
 * @param correlationId cross-system correlation (trace id, request id), or {@code null}
 * @param attributes extra string attributes copied onto audit events and spans
 */
public record RunContext(
    @Nullable String tenantId,
    @Nullable String principal,
    @Nullable String agentId,
    @Nullable String sessionId,
    String runId,
    @Nullable String correlationId,
    Map<String, String> attributes) {

  public RunContext {
    Objects.requireNonNull(runId, "runId");
    attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
  }

  /** A context with a fresh random run id and nothing else; for tests and single-user tools. */
  public static RunContext anonymous() {
    return new RunContext(null, null, null, null, UUID.randomUUID().toString(), null, Map.of());
  }

  /** A context for the given run id and nothing else. */
  public static RunContext ofRun(String runId) {
    return new RunContext(null, null, null, null, runId, null, Map.of());
  }

  public static Builder builder() {
    return new Builder();
  }

  public Builder toBuilder() {
    return new Builder()
        .tenantId(tenantId)
        .principal(principal)
        .agentId(agentId)
        .sessionId(sessionId)
        .runId(runId)
        .correlationId(correlationId)
        .attributes(attributes);
  }

  /** The principal, or {@code "anonymous"} when none is set. */
  public String principalOrAnonymous() {
    return principal == null ? "anonymous" : principal;
  }

  public static final class Builder {
    private @Nullable String tenantId;
    private @Nullable String principal;
    private @Nullable String agentId;
    private @Nullable String sessionId;
    private @Nullable String runId;
    private @Nullable String correlationId;
    private final Map<String, String> attributes = new LinkedHashMap<>();

    private Builder() {}

    public Builder tenantId(@Nullable String tenantId) {
      this.tenantId = tenantId;
      return this;
    }

    public Builder principal(@Nullable String principal) {
      this.principal = principal;
      return this;
    }

    public Builder agentId(@Nullable String agentId) {
      this.agentId = agentId;
      return this;
    }

    public Builder sessionId(@Nullable String sessionId) {
      this.sessionId = sessionId;
      return this;
    }

    public Builder runId(String runId) {
      this.runId = runId;
      return this;
    }

    public Builder correlationId(@Nullable String correlationId) {
      this.correlationId = correlationId;
      return this;
    }

    public Builder attribute(String key, String value) {
      attributes.put(key, value);
      return this;
    }

    public Builder attributes(Map<String, String> values) {
      attributes.putAll(values);
      return this;
    }

    public RunContext build() {
      String id = runId == null ? UUID.randomUUID().toString() : runId;
      return new RunContext(tenantId, principal, agentId, sessionId, id, correlationId, attributes);
    }
  }
}
