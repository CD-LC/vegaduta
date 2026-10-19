package io.vegaduta.agent.api.risk;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * What a tool can do to the world. The dispatch gate, the approval policy, the effect journal and
 * the lethal-trifecta detector all read from this record.
 *
 * @param toolName the tool the classification belongs to
 * @param risk coarse damage potential
 * @param sideEffecting changes state outside the process
 * @param readsPrivateData returns data that must not leak
 * @param consumesUntrustedInput returns content from an untrusted party
 * @param exfiltrationSink can move data out of the trust boundary
 * @param idempotent safe to replay with the same arguments
 * @param requiresApproval a human must approve each call regardless of policy
 * @param risks OWASP ASI items the tool is exposed to
 */
public record ToolClassification(
    String toolName,
    RiskLabel risk,
    boolean sideEffecting,
    boolean readsPrivateData,
    boolean consumesUntrustedInput,
    boolean exfiltrationSink,
    boolean idempotent,
    boolean requiresApproval,
    Set<AsiRisk> risks) {

  public ToolClassification {
    Objects.requireNonNull(toolName, "toolName");
    Objects.requireNonNull(risk, "risk");
    risks =
        risks.isEmpty()
            ? Collections.emptySet()
            : Collections.unmodifiableSet(EnumSet.copyOf(risks));
  }

  /** A read-only, low-risk classification; the usual default for lookups and searches. */
  public static ToolClassification readOnly(String toolName) {
    return new ToolClassification(
        toolName, RiskLabel.LOW, false, false, false, false, true, false, Set.of());
  }

  public static Builder builder(String toolName) {
    return new Builder(toolName);
  }

  public Builder toBuilder() {
    return new Builder(toolName)
        .risk(risk)
        .sideEffecting(sideEffecting)
        .readsPrivateData(readsPrivateData)
        .consumesUntrustedInput(consumesUntrustedInput)
        .exfiltrationSink(exfiltrationSink)
        .idempotent(idempotent)
        .requiresApproval(requiresApproval)
        .risks(risks);
  }

  public ToolClassification withToolName(String name) {
    return toBuilder().toolName(name).build();
  }

  /** True when a replay of a previous result is acceptable instead of calling again. */
  public boolean replaySafe() {
    return idempotent || !sideEffecting;
  }

  public static final class Builder {
    private String toolName;
    private RiskLabel risk = RiskLabel.MEDIUM;
    private boolean sideEffecting = true;
    private boolean readsPrivateData;
    private boolean consumesUntrustedInput;
    private boolean exfiltrationSink;
    private boolean idempotent;
    private boolean requiresApproval;
    private final Set<AsiRisk> risks = EnumSet.noneOf(AsiRisk.class);

    private Builder(String toolName) {
      this.toolName = Objects.requireNonNull(toolName, "toolName");
    }

    public Builder toolName(String value) {
      this.toolName = Objects.requireNonNull(value, "toolName");
      return this;
    }

    public Builder risk(RiskLabel value) {
      this.risk = Objects.requireNonNull(value, "risk");
      return this;
    }

    public Builder sideEffecting(boolean value) {
      this.sideEffecting = value;
      return this;
    }

    public Builder readsPrivateData(boolean value) {
      this.readsPrivateData = value;
      return this;
    }

    public Builder consumesUntrustedInput(boolean value) {
      this.consumesUntrustedInput = value;
      return this;
    }

    public Builder exfiltrationSink(boolean value) {
      this.exfiltrationSink = value;
      return this;
    }

    public Builder idempotent(boolean value) {
      this.idempotent = value;
      return this;
    }

    public Builder requiresApproval(boolean value) {
      this.requiresApproval = value;
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

    public ToolClassification build() {
      return new ToolClassification(
          toolName,
          risk,
          sideEffecting,
          readsPrivateData,
          consumesUntrustedInput,
          exfiltrationSink,
          idempotent,
          requiresApproval,
          risks);
    }
  }
}
