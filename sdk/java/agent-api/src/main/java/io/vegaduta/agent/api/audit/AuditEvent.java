package io.vegaduta.agent.api.audit;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.risk.AsiRisk;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One audited action. The first eight fields match VegaDuta's published audit contract ({@code
 * contracts/outbound/audit-event.schema.json}); the rest add run scope and risk tags.
 *
 * @param id unique id
 * @param createdAt when it happened
 * @param actorId who did it: the principal, or the agent id when no principal is known
 * @param actionType what, for example {@code "tool.call"}, {@code "approval.decided"}
 * @param targetType what kind of thing, for example {@code "tool"}, {@code "approval"}
 * @param targetId which thing, for example the tool name or the approval id
 * @param outcome how it ended
 * @param detail free text, or {@code null}
 * @param context the run
 * @param risks OWASP ASI items the action relates to
 * @param attributes extra key/value data
 */
public record AuditEvent(
    UUID id,
    Instant createdAt,
    String actorId,
    String actionType,
    String targetType,
    String targetId,
    AuditOutcome outcome,
    @Nullable String detail,
    RunContext context,
    Set<AsiRisk> risks,
    Map<String, String> attributes) {

  public AuditEvent {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(actionType, "actionType");
    Objects.requireNonNull(targetType, "targetType");
    Objects.requireNonNull(targetId, "targetId");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(context, "context");
    risks =
        risks.isEmpty()
            ? Collections.emptySet()
            : Collections.unmodifiableSet(EnumSet.copyOf(risks));
    attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
  }

  public static Builder builder(String actionType, String targetType, String targetId) {
    return new Builder(actionType, targetType, targetId);
  }

  /** The event as flat key/value pairs, for logs and simple sinks. */
  public Map<String, String> toFlatMap() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("id", id.toString());
    map.put("createdAt", createdAt.toString());
    map.put("actorId", actorId);
    map.put("actionType", actionType);
    map.put("targetType", targetType);
    map.put("targetId", targetId);
    map.put("outcome", outcome.name());
    if (detail != null) {
      map.put("detail", detail);
    }
    map.put("runId", context.runId());
    putIfPresent(map, "tenantId", context.tenantId());
    putIfPresent(map, "agentId", context.agentId());
    putIfPresent(map, "sessionId", context.sessionId());
    putIfPresent(map, "correlationId", context.correlationId());
    if (!risks.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      for (AsiRisk r : risks) {
        if (sb.length() > 0) {
          sb.append(',');
        }
        sb.append(r.id());
      }
      map.put("risks", sb.toString());
    }
    map.putAll(attributes);
    return map;
  }

  private static void putIfPresent(Map<String, String> map, String key, @Nullable String value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  public static final class Builder {
    private final String actionType;
    private final String targetType;
    private final String targetId;
    private @Nullable String actorId;
    private AuditOutcome outcome = AuditOutcome.SUCCESS;
    private @Nullable String detail;
    private @Nullable RunContext context;
    private @Nullable Instant createdAt;
    private final Set<AsiRisk> risks = EnumSet.noneOf(AsiRisk.class);
    private final Map<String, String> attributes = new LinkedHashMap<>();

    private Builder(String actionType, String targetType, String targetId) {
      this.actionType = Objects.requireNonNull(actionType, "actionType");
      this.targetType = Objects.requireNonNull(targetType, "targetType");
      this.targetId = Objects.requireNonNull(targetId, "targetId");
    }

    public Builder actor(String value) {
      this.actorId = value;
      return this;
    }

    public Builder outcome(AuditOutcome value) {
      this.outcome = value;
      return this;
    }

    public Builder detail(@Nullable String value) {
      this.detail = value;
      return this;
    }

    public Builder context(RunContext value) {
      this.context = value;
      return this;
    }

    public Builder at(Instant value) {
      this.createdAt = value;
      return this;
    }

    public Builder risks(Set<AsiRisk> values) {
      this.risks.addAll(values);
      return this;
    }

    public Builder risk(AsiRisk value) {
      this.risks.add(value);
      return this;
    }

    public Builder attribute(String key, String value) {
      this.attributes.put(key, value);
      return this;
    }

    public AuditEvent build() {
      RunContext ctx = context == null ? RunContext.anonymous() : context;
      String actor = actorId;
      if (actor == null) {
        actor = ctx.principal() != null ? ctx.principal() : ctx.agentId();
      }
      if (actor == null) {
        actor = "anonymous";
      }
      return new AuditEvent(
          UUID.randomUUID(),
          createdAt == null ? Instant.now() : createdAt,
          actor,
          actionType,
          targetType,
          targetId,
          outcome,
          detail,
          ctx,
          risks,
          attributes);
    }
  }
}
