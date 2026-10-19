package io.vegaduta.agent.core.replay;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.gate.ToolDispatchGate;
import io.vegaduta.agent.api.gate.Verdict;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.core.GovernancePolicy;
import io.vegaduta.agent.core.audit.InMemoryAuditSink;
import io.vegaduta.agent.core.classification.ClassificationResolver;
import io.vegaduta.agent.core.classification.StaticClassificationSource;
import io.vegaduta.agent.core.gate.PolicyToolDispatchGate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShadowGateTest {

  private static final RunContext CTX = RunContext.ofRun("run-7");
  private static final List<ToolClassification> TOOLS =
      List.of(
          ToolClassification.readOnly("search"),
          ToolClassification.builder("send_email").risk(RiskLabel.MEDIUM).build());

  private final InMemoryAuditSink audit = new InMemoryAuditSink();

  private static PolicyToolDispatchGate gate(GovernancePolicy policy) {
    return new PolicyToolDispatchGate(
        new ClassificationResolver(new StaticClassificationSource(TOOLS), policy));
  }

  @Test
  void liveVerdictIsReturnedUnchangedAndDivergenceIsAudited() {
    ToolDispatchGate live = gate(GovernancePolicy.defaults());
    ToolDispatchGate candidate =
        gate(GovernancePolicy.builder().approvalRequiredFrom(RiskLabel.MEDIUM).build());
    ShadowGate shadow = new ShadowGate(live, candidate, audit);

    Verdict email = shadow.check(new ToolCall("k1", "send_email", "{}"), CTX);
    Verdict search = shadow.check(new ToolCall("k2", "search", "{}"), CTX);

    assertThat(email).isEqualTo(Verdict.allow());
    assertThat(search).isEqualTo(Verdict.allow());
    assertThat(shadow.evaluated()).isEqualTo(2);
    assertThat(shadow.divergences()).isEqualTo(1);
    assertThat(audit.events()).hasSize(1);
    AuditEvent event = audit.events().get(0);
    assertThat(event.actionType()).isEqualTo("shadow.decision");
    assertThat(event.targetType()).isEqualTo("tool");
    assertThat(event.targetId()).isEqualTo("send_email");
    assertThat(event.outcome()).isEqualTo(AuditOutcome.WARNING);
    assertThat(event.risks()).containsExactly(AsiRisk.ASI02);
    assertThat(event.context()).isEqualTo(CTX);
    assertThat(event.attributes())
        .containsEntry("callId", "k1")
        .containsEntry("live", "ALLOW")
        .containsEntry("shadow", "REQUIRE_APPROVAL")
        .containsEntry("shadowReason", PolicyToolDispatchGate.REASON_RISK_THRESHOLD)
        .containsEntry("change", "NEWLY_ESCALATED");
  }

  @Test
  void noEventWhenBothGatesAgree() {
    ToolDispatchGate live = gate(GovernancePolicy.defaults());
    ShadowGate shadow = new ShadowGate(live, gate(GovernancePolicy.defaults()), audit);

    shadow.check(new ToolCall("k1", "send_email", "{}"), CTX);
    shadow.check(new ToolCall("k2", "search", "{}"), CTX);

    assertThat(shadow.divergences()).isZero();
    assertThat(audit.events()).isEmpty();
  }

  @Test
  void liveDenyStandsEvenWhenShadowWouldAllow() {
    ToolDispatchGate live = gate(GovernancePolicy.defaults()).deny("send_email");
    ShadowGate shadow = new ShadowGate(live, gate(GovernancePolicy.defaults()), audit);

    Verdict verdict = shadow.check(new ToolCall("k1", "send_email", "{}"), CTX);

    assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.DENY);
    assertThat(verdict.reason()).isEqualTo(PolicyToolDispatchGate.REASON_DENY_LIST);
    assertThat(audit.events().get(0).attributes()).containsEntry("change", "RELAXED");
  }

  @Test
  void aThrowingShadowGateNeverReachesTheLivePath() {
    ToolDispatchGate boom =
        (call, context) -> {
          throw new IllegalStateException("shadow broke");
        };
    ShadowGate shadow = new ShadowGate(ToolDispatchGate.allowAll(), boom, audit);

    Verdict verdict = shadow.check(new ToolCall("k1", "search", "{}"), CTX);

    assertThat(verdict).isEqualTo(Verdict.allow());
    assertThat(shadow.shadowErrors()).isEqualTo(1);
    assertThat(shadow.divergences()).isZero();
    assertThat(audit.events()).isEmpty();
  }

  @Test
  void shadowReportSummarisesDivergencesPerTool() {
    ToolDispatchGate live = gate(GovernancePolicy.defaults());
    ToolDispatchGate candidate =
        gate(GovernancePolicy.builder().approvalRequiredFrom(RiskLabel.MEDIUM).build())
            .deny("search");
    ShadowGate shadow = new ShadowGate(live, candidate, audit);
    shadow.check(new ToolCall("k1", "send_email", "{}"), CTX);
    shadow.check(new ToolCall("k2", "send_email", "{}"), CTX);
    shadow.check(new ToolCall("k3", "search", "{}"), CTX);
    // Unrelated events are ignored.
    audit.emit(AuditEvent.builder("tool.call", "tool", "search").context(CTX).build());

    ShadowReport report = ShadowReport.from(audit.events());

    assertThat(report.divergences()).isEqualTo(3);
    assertThat(report.perTool()).hasSize(2);
    ShadowReport.ToolRow email = report.perTool().get(0);
    assertThat(email.toolName()).isEqualTo("send_email");
    assertThat(email.divergences()).isEqualTo(2);
    assertThat(email.wouldEscalate()).isEqualTo(2);
    assertThat(email.wouldBlock()).isZero();
    assertThat(email.exampleCallIds()).containsExactly("k1", "k2");
    ShadowReport.ToolRow search = report.perTool().get(1);
    assertThat(search.wouldBlock()).isEqualTo(1);
    assertThat(report.from()).isNotNull();
    assertThat(report.toMarkdown())
        .contains("Divergences: 3")
        .contains("| send_email | 2 | 0 | 2 | 0 | k1, k2 |")
        .contains("| search | 1 | 1 | 0 | 0 | k3 |");
    String[] lines = report.toJsonLines().split("\n");
    assertThat(lines).hasSize(3);
    assertThat(lines[0]).contains("\"divergences\":\"3\"");
    assertThat(lines[2]).contains("\"tool\":\"search\"").contains("\"wouldBlock\":\"1\"");
  }

  @Test
  void emptyShadowReport() {
    ShadowReport report = ShadowReport.from(List.of());

    assertThat(report.divergences()).isZero();
    assertThat(report.perTool()).isEmpty();
    assertThat(report.from()).isNull();
    assertThat(report.toJsonLines()).isEqualTo("{\"type\":\"summary\",\"divergences\":\"0\"}\n");
  }
}
