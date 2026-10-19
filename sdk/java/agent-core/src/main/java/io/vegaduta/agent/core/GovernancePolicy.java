package io.vegaduta.agent.core;

import io.vegaduta.agent.api.risk.RiskLabel;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/**
 * The knobs. Every field has a safe default so {@code GovernancePolicy.defaults()} is a reasonable
 * production starting point.
 *
 * @param mode how unknown tools are treated
 * @param defaultRisk the classification an unclassified tool gets in {@link GovernanceMode#WARN}
 * @param approvalRequiredFrom tools at or above this risk need a human; {@code null} disables the
 *     threshold (explicit {@code requiresApproval} still applies)
 * @param approvalTtl how long an approval request stays decidable
 * @param fourEyes the requester may not approve their own request
 * @param maxToolCallsPerRun budget cap on tool calls per run; {@code 0} means unlimited
 * @param maxTokensPerRun budget cap on tokens per run; {@code 0} means unlimited
 * @param maxUsdPerRun budget cap on money per run; zero means unlimited
 * @param estimatedUsdPerToolCall reserved per tool call when the real cost is unknown
 * @param effectJournalTtl how long replayable results are kept
 * @param argumentPreviewChars how much of the arguments goes into approval summaries and audit
 */
public record GovernancePolicy(
    GovernanceMode mode,
    RiskLabel defaultRisk,
    RiskLabel approvalRequiredFrom,
    Duration approvalTtl,
    boolean fourEyes,
    long maxToolCallsPerRun,
    long maxTokensPerRun,
    BigDecimal maxUsdPerRun,
    BigDecimal estimatedUsdPerToolCall,
    Duration effectJournalTtl,
    int argumentPreviewChars) {

  public GovernancePolicy {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(defaultRisk, "defaultRisk");
    Objects.requireNonNull(approvalRequiredFrom, "approvalRequiredFrom");
    Objects.requireNonNull(approvalTtl, "approvalTtl");
    Objects.requireNonNull(maxUsdPerRun, "maxUsdPerRun");
    Objects.requireNonNull(estimatedUsdPerToolCall, "estimatedUsdPerToolCall");
    Objects.requireNonNull(effectJournalTtl, "effectJournalTtl");
    if (maxToolCallsPerRun < 0 || maxTokensPerRun < 0 || maxUsdPerRun.signum() < 0) {
      throw new IllegalArgumentException("budget caps must not be negative");
    }
    if (argumentPreviewChars < 0) {
      throw new IllegalArgumentException("argumentPreviewChars must not be negative");
    }
  }

  public static GovernancePolicy defaults() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public Builder toBuilder() {
    return new Builder()
        .mode(mode)
        .defaultRisk(defaultRisk)
        .approvalRequiredFrom(approvalRequiredFrom)
        .approvalTtl(approvalTtl)
        .fourEyes(fourEyes)
        .maxToolCallsPerRun(maxToolCallsPerRun)
        .maxTokensPerRun(maxTokensPerRun)
        .maxUsdPerRun(maxUsdPerRun)
        .estimatedUsdPerToolCall(estimatedUsdPerToolCall)
        .effectJournalTtl(effectJournalTtl)
        .argumentPreviewChars(argumentPreviewChars);
  }

  public static final class Builder {
    private GovernanceMode mode = GovernanceMode.WARN;
    private RiskLabel defaultRisk = RiskLabel.MEDIUM;
    private RiskLabel approvalRequiredFrom = RiskLabel.HIGH;
    private Duration approvalTtl = Duration.ofHours(24);
    private boolean fourEyes = true;
    private long maxToolCallsPerRun = 50;
    private long maxTokensPerRun = 0;
    private BigDecimal maxUsdPerRun = BigDecimal.ZERO;
    private BigDecimal estimatedUsdPerToolCall = BigDecimal.ZERO;
    private Duration effectJournalTtl = Duration.ofHours(1);
    private int argumentPreviewChars = 500;

    private Builder() {}

    public Builder mode(GovernanceMode value) {
      this.mode = value;
      return this;
    }

    public Builder defaultRisk(RiskLabel value) {
      this.defaultRisk = value;
      return this;
    }

    public Builder approvalRequiredFrom(RiskLabel value) {
      this.approvalRequiredFrom = value;
      return this;
    }

    public Builder approvalTtl(Duration value) {
      this.approvalTtl = value;
      return this;
    }

    public Builder fourEyes(boolean value) {
      this.fourEyes = value;
      return this;
    }

    public Builder maxToolCallsPerRun(long value) {
      this.maxToolCallsPerRun = value;
      return this;
    }

    public Builder maxTokensPerRun(long value) {
      this.maxTokensPerRun = value;
      return this;
    }

    public Builder maxUsdPerRun(BigDecimal value) {
      this.maxUsdPerRun = value;
      return this;
    }

    public Builder estimatedUsdPerToolCall(BigDecimal value) {
      this.estimatedUsdPerToolCall = value;
      return this;
    }

    public Builder effectJournalTtl(Duration value) {
      this.effectJournalTtl = value;
      return this;
    }

    public Builder argumentPreviewChars(int value) {
      this.argumentPreviewChars = value;
      return this;
    }

    public GovernancePolicy build() {
      return new GovernancePolicy(
          mode,
          defaultRisk,
          approvalRequiredFrom,
          approvalTtl,
          fourEyes,
          maxToolCallsPerRun,
          maxTokensPerRun,
          maxUsdPerRun,
          estimatedUsdPerToolCall,
          effectJournalTtl,
          argumentPreviewChars);
    }
  }
}
