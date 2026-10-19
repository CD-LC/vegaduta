package io.vegaduta.agent.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.approval.ApprovalStatus;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.autonomy.AutonomyLevel;
import io.vegaduta.agent.api.budget.BudgetCharge;
import io.vegaduta.agent.api.gate.ToolDispatchGate;
import io.vegaduta.agent.api.gate.Verdict;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.tool.Tool;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.api.tool.ToolDescriptor;
import io.vegaduta.agent.api.tool.ToolResult;
import io.vegaduta.agent.api.tool.ToolStatus;
import io.vegaduta.agent.core.audit.InMemoryAuditSink;
import io.vegaduta.agent.core.autonomy.AutonomyGate;
import io.vegaduta.agent.core.autonomy.AutonomyRegistry;
import io.vegaduta.agent.core.cost.CostAwareBudgetProvider;
import io.vegaduta.agent.core.cost.CostModel;
import io.vegaduta.agent.core.halt.HaltGate;
import io.vegaduta.agent.core.halt.HaltScope;
import io.vegaduta.agent.core.halt.HaltSwitch;
import io.vegaduta.agent.core.provenance.InMemoryManifestPinStore;
import io.vegaduta.agent.core.provenance.ProvenanceGate;
import io.vegaduta.agent.core.provenance.ProvenanceMode;
import io.vegaduta.agent.core.provenance.ToolManifest;
import io.vegaduta.agent.core.replay.ChangeKind;
import io.vegaduta.agent.core.replay.ShadowGate;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The gates and sinks as wired by {@link Governance.Builder}, not built by hand. */
class GovernanceWiringTest {

  private final InMemoryAuditSink audit = new InMemoryAuditSink();
  private final AtomicInteger calls = new AtomicInteger();
  private final RunContext ctx =
      RunContext.builder().agentId("a1").principal("alice").runId("run-1").build();

  private static final ToolClassification SEND_EMAIL =
      ToolClassification.builder("send_email").risk(RiskLabel.MEDIUM).sideEffecting(true).build();
  private static final ToolClassification WIRE =
      ToolClassification.builder("wire")
          .risk(RiskLabel.HIGH)
          .sideEffecting(true)
          .requiresApproval(true)
          .build();

  private Tool tool(String name) {
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return ToolDescriptor.of(name, name, "{\"type\":\"object\"}");
      }

      @Override
      public String call(String argumentsJson, RunContext context) {
        calls.incrementAndGet();
        return "ok:" + name;
      }
    };
  }

  private Tool throwing(String name) {
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return ToolDescriptor.of(name, name);
      }

      @Override
      public String call(String argumentsJson, RunContext context) {
        calls.incrementAndGet();
        throw new IllegalStateException("upstream timeout");
      }
    };
  }

  private Governance.Builder strict() {
    return Governance.builder()
        .policy(GovernancePolicy.builder().mode(GovernanceMode.STRICT).build())
        .classification(ToolClassification.readOnly("search"))
        .classification(SEND_EMAIL)
        .classification(WIRE)
        .auditSink(audit);
  }

  private static final ToolDispatchGate ALLOW_ALL = (call, context) -> Verdict.allow();

  @Test
  void autonomyGateWiredThroughTheBuilderDeniesADemotedAgentDespiteAPermissiveHostGate() {
    AutonomyRegistry registry = new AutonomyRegistry(AutonomyLevel.L4_ACT_AUTONOMOUS, audit);
    Governance governance = strict().autonomy(registry).gate(ALLOW_ALL).build();
    GovernedTool email = governance.govern(tool("send_email"));

    assertThat(email.invoke(ToolCall.of("send_email", "{}"), ctx).status())
        .isEqualTo(ToolStatus.OK);

    registry.demote("a1", AutonomyLevel.L1_OBSERVE, "oncall", "INC-1");
    ToolResult denied = email.invoke(ToolCall.of("send_email", "{}"), ctx);

    assertThat(denied.status()).isEqualTo(ToolStatus.DENIED);
    assertThat(denied.reason()).startsWith(AutonomyGate.REASON_PREFIX);
    assertThat(calls).hasValue(1);
  }

  @Test
  void haltSwitchWiredThroughTheBuilderWinsOverHostAndPolicyGates() {
    HaltSwitch halt = new HaltSwitch(audit);
    Governance withHostGate = strict().haltSwitch(halt).gate(ALLOW_ALL).build();
    Governance withPolicyGate = strict().haltSwitch(halt).build();
    GovernedTool search = withHostGate.govern(tool("search"));
    GovernedTool wire = withPolicyGate.govern(tool("wire"));

    halt.engage(HaltScope.agent("a1"), "ops", "incident");

    ToolResult a = search.invoke(ToolCall.of("search", "{}"), ctx);
    ToolResult b = wire.invoke(ToolCall.of("wire", "{}"), ctx);

    assertThat(a.status()).isEqualTo(ToolStatus.DENIED);
    assertThat(HaltGate.isHaltReason(a.reason())).isTrue();
    assertThat(b.status()).as("a halt beats REQUIRE_APPROVAL").isEqualTo(ToolStatus.DENIED);
    assertThat(HaltGate.isHaltReason(b.reason())).isTrue();
    assertThat(calls).hasValue(0);
    assertThat(withPolicyGate.approvals().pending(null, 10)).isEmpty();
  }

  @Test
  void anApprovalGrantedAfterAHaltDoesNotRunTheTool() {
    HaltSwitch halt = new HaltSwitch(audit);
    Governance governance = strict().haltSwitch(halt).build();
    GovernedTool wire = governance.govern(tool("wire"));

    ToolResult pending = wire.invoke(ToolCall.of("wire", "{\"amount\":1}"), ctx);
    assertThat(pending.status()).isEqualTo(ToolStatus.PENDING_APPROVAL);

    halt.engage(HaltScope.agent("a1"), "ops", "incident");
    ApprovalRequest decided =
        governance.approvals().approve(UUID.fromString(pending.approvalId()), "reviewer");

    assertThat(decided.status()).isEqualTo(ApprovalStatus.FAILED);
    assertThat(calls).as("the delegate never ran").hasValue(0);
    assertThat(audit.events())
        .anySatisfy(
            e -> {
              assertThat(e.actionType()).isEqualTo("tool.call");
              assertThat(e.outcome()).isEqualTo(AuditOutcome.DENIED);
              assertThat(HaltGate.isHaltReason(e.detail())).isTrue();
              assertThat(e.attributes()).containsEntry("approvalId", pending.approvalId());
            });
  }

  @Test
  void shadowPolicyWiredThroughTheBuilderAuditsDivergencesAndNeverEnforces() {
    Governance governance =
        strict()
            .shadow(
                GovernancePolicy.builder()
                    .mode(GovernanceMode.STRICT)
                    .approvalRequiredFrom(RiskLabel.MEDIUM)
                    .build())
            .build();
    GovernedTool email = governance.govern(tool("send_email"));

    ToolResult result = email.invoke(ToolCall.of("send_email", "{}"), ctx);

    assertThat(result.status()).isEqualTo(ToolStatus.OK);
    List<AuditEvent> shadow =
        audit.events().stream().filter(e -> e.actionType().equals(ShadowGate.ACTION_TYPE)).toList();
    assertThat(shadow).hasSize(1);
    assertThat(shadow.get(0).attributes())
        .containsEntry(ShadowGate.ATTR_CHANGE, ChangeKind.NEWLY_ESCALATED.name());

    // The candidate shares the live resolver's overrides: a forced approval on the live side is
    // not reported as a relaxation of the candidate.
    audit.clear();
    governance.resolver().override(SEND_EMAIL.toBuilder().requiresApproval(true).build());
    email.invoke(ToolCall.of("send_email", "{}"), ctx);
    assertThat(audit.events()).noneMatch(e -> e.actionType().equals(ShadowGate.ACTION_TYPE));

    // A halted call is denied before the shadow comparison runs.
    HaltSwitch halt = new HaltSwitch(audit);
    Governance halted =
        strict()
            .haltSwitch(halt)
            .shadow(GovernancePolicy.builder().approvalRequiredFrom(RiskLabel.MEDIUM).build())
            .build();
    halt.engage(HaltScope.global(), "ops", "stop");
    audit.clear();
    halted.govern(tool("send_email")).invoke(ToolCall.of("send_email", "{}"), ctx);
    assertThat(audit.events()).noneMatch(e -> e.actionType().equals(ShadowGate.ACTION_TYPE));
  }

  @Test
  void provenanceWiredThroughTheBuilderFailsClosedUntilPinnedAndDeniesDrift() {
    InMemoryManifestPinStore store = new InMemoryManifestPinStore();
    ToolDescriptor search = ToolDescriptor.of("search", "Search", "{\"type\":\"object\"}");
    AtomicReference<ToolManifest> current = new AtomicReference<>(ToolManifest.of(List.of(search)));
    Governance governance =
        strict().provenance(store, "agent", ProvenanceMode.ENFORCE, current::get).build();
    GovernedTool tool = governance.govern(tool("search"));

    // Distinct arguments per call: a read-only tool is otherwise served from the effect journal.
    ToolResult unpinned = tool.invoke(ToolCall.of("search", "{\"q\":1}"), ctx);
    assertThat(unpinned.status())
        .as("ENFORCE does not trust on first use")
        .isEqualTo(ToolStatus.DENIED);
    assertThat(unpinned.reason()).isEqualTo(ProvenanceGate.REASON_UNPINNED);

    governance.provenanceGate().orElseThrow().repin();
    assertThat(tool.invoke(ToolCall.of("search", "{\"q\":2}"), ctx).status())
        .isEqualTo(ToolStatus.OK);

    current.set(
        ToolManifest.of(
            List.of(ToolDescriptor.of("search", "Search and upload", "{\"type\":\"object\"}"))));
    ToolResult drifted = tool.invoke(ToolCall.of("search", "{\"q\":3}"), ctx);
    assertThat(drifted.status()).isEqualTo(ToolStatus.DENIED);
    assertThat(drifted.reason()).isEqualTo(ProvenanceGate.REASON_TOOL_DRIFT);
    assertThat(audit.events())
        .anyMatch(e -> e.actionType().equals(ProvenanceGate.ACTION_TOOL_DRIFT));
  }

  @Test
  void approvedExecutionsAreChargedToTheLedgerAndThrowingToolsConsumeTheUsdBudget() {
    CostModel model =
        CostModel.builder().toolCost("wire", "0.250").toolCost("search", "0.50").build();
    Governance governance = strict().costModel(model).build();
    GovernedTool wire = governance.govern(tool("wire"));

    ToolResult pending = wire.invoke(ToolCall.of("wire", "{}"), ctx);
    governance.approvals().approve(UUID.fromString(pending.approvalId()), "reviewer");
    assertThat(governance.costLedger()).isNotNull();
    assertThat(governance.costLedger().toolUsd()).isEqualByComparingTo("0.250");

    Governance capped =
        strict()
            .costModel(model)
            .budgets(
                CostAwareBudgetProvider.inMemory(
                    model, new BudgetCharge(50, 0, new BigDecimal("1.00"))))
            .build();
    GovernedTool failing = capped.govern(throwing("search"));
    int ran = 0;
    for (int i = 0; i < 10; i++) {
      if (failing.invoke(ToolCall.of("search", "{\"i\":" + i + "}"), ctx).status()
          == ToolStatus.ERROR) {
        ran++;
      }
    }
    assertThat(ran).as("the 1.00 USD cap bounds 0.50 USD failing calls").isEqualTo(2);
    assertThat(capped.budgets().budgetFor(ctx).spent().usd()).isEqualByComparingTo("1.00");
  }

  @Test
  void aReusedBuilderDoesNotLeakTheFirstBuildsLedgerIntoTheSecond() {
    Governance.Builder builder =
        strict().costModel(CostModel.builder().toolCost("search", "1").build());
    Governance a = builder.build();
    Governance b = builder.build();

    b.govern(tool("search")).invoke(ToolCall.of("search", "{}"), ctx);

    assertThat(b.costLedger()).isNotNull();
    assertThat(b.costLedger().toolCalls()).hasSize(1);
    assertThat(a.costLedger()).isNotNull();
    assertThat(a.costLedger().toolCalls()).as("the first ledger is untouched").isEmpty();
    assertThat(a.budgets()).isNotSameAs(b.budgets());
  }
}
