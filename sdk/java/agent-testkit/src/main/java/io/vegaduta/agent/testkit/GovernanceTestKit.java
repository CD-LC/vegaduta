package io.vegaduta.agent.testkit;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.tool.Tool;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.api.tool.ToolResult;
import io.vegaduta.agent.core.Governance;
import io.vegaduta.agent.core.GovernanceMode;
import io.vegaduta.agent.core.GovernancePolicy;
import io.vegaduta.agent.core.GovernedTool;
import io.vegaduta.agent.core.audit.InMemoryAuditSink;
import io.vegaduta.agent.core.hook.HookRegistration;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A {@link Governance} wired for tests: in-memory stores, a recording audit sink, and helpers to
 * invoke tools and decide approvals in one line.
 *
 * <pre>{@code
 * GovernanceTestKit kit = GovernanceTestKit.strict()
 *     .classification(ToolClassification.builder("refund").requiresApproval(true).build())
 *     .build();
 * ToolResult result = kit.invoke(FakeTool.echo("refund"), "{}", RunContext.anonymous());
 * assertThat(result).isPendingApproval();
 * kit.approveAll("reviewer");
 * assertThat(kit.audit()).hasSucceeded("refund");
 * }</pre>
 */
public final class GovernanceTestKit {

  private final Governance governance;
  private final InMemoryAuditSink audit;

  private GovernanceTestKit(Governance governance, InMemoryAuditSink audit) {
    this.governance = governance;
    this.audit = audit;
  }

  public static Builder strict() {
    return new Builder(GovernancePolicy.builder().mode(GovernanceMode.STRICT).build());
  }

  public static Builder warn() {
    return new Builder(GovernancePolicy.builder().mode(GovernanceMode.WARN).build());
  }

  public static Builder of(GovernancePolicy policy) {
    return new Builder(policy);
  }

  public Governance governance() {
    return governance;
  }

  public InMemoryAuditSink audit() {
    return audit;
  }

  public List<AuditEvent> events() {
    return audit.events();
  }

  public GovernedTool govern(Tool tool) {
    return governance.govern(tool);
  }

  public ToolResult invoke(Tool tool, String argumentsJson, RunContext context) {
    return governance.govern(tool).invoke(ToolCall.of(tool.name(), argumentsJson), context);
  }

  public ToolResult invoke(Tool tool, String argumentsJson) {
    return invoke(tool, argumentsJson, RunContext.anonymous());
  }

  public List<ApprovalRequest> pending() {
    return governance.approvals().pending(null, Integer.MAX_VALUE);
  }

  /** Approves every pending request as {@code decidedBy} and returns the decided requests. */
  public List<ApprovalRequest> approveAll(String decidedBy) {
    return pending().stream().map(r -> governance.approvals().approve(r.id(), decidedBy)).toList();
  }

  public List<ApprovalRequest> rejectAll(String decidedBy, @Nullable String reason) {
    return pending().stream()
        .map(r -> governance.approvals().reject(r.id(), decidedBy, reason))
        .toList();
  }

  public static final class Builder {
    private final Governance.Builder delegate;
    private final InMemoryAuditSink audit = new InMemoryAuditSink();

    private Builder(GovernancePolicy policy) {
      this.delegate = Governance.builder().policy(policy).auditSink(audit);
    }

    public Builder classification(ToolClassification value) {
      delegate.classification(value);
      return this;
    }

    public Builder classifications(Collection<ToolClassification> values) {
      delegate.classifications(values);
      return this;
    }

    public Builder hook(HookRegistration value) {
      delegate.hook(value);
      return this;
    }

    /** Any other customisation of the underlying {@link Governance.Builder}. */
    public Builder customize(Consumer<Governance.Builder> customizer) {
      customizer.accept(delegate);
      return this;
    }

    public GovernanceTestKit build() {
      return new GovernanceTestKit(delegate.build(), audit);
    }
  }
}
