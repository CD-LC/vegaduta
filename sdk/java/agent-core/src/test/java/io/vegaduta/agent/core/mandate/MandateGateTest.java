package io.vegaduta.agent.core.mandate;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.gate.ToolDispatchGate;
import io.vegaduta.agent.api.gate.Verdict;
import io.vegaduta.agent.api.mandate.Mandate;
import io.vegaduta.agent.api.mandate.Mandates;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.core.GovernanceMode;
import io.vegaduta.agent.core.GovernancePolicy;
import io.vegaduta.agent.core.classification.ClassificationResolver;
import io.vegaduta.agent.core.classification.StaticClassificationSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MandateGateTest {

  private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final ClassificationResolver resolver =
      new ClassificationResolver(
          new StaticClassificationSource(
              List.of(
                  ToolClassification.readOnly("crm.read"),
                  ToolClassification.builder("crm.delete").risk(RiskLabel.HIGH).build(),
                  ToolClassification.builder("jira.issue.create").risk(RiskLabel.MEDIUM).build())),
          GovernancePolicy.builder()
              .mode(GovernanceMode.WARN)
              .defaultRisk(RiskLabel.MEDIUM)
              .build());

  private final AtomicInteger delegateCalls = new AtomicInteger();
  private final ToolDispatchGate countingDelegate =
      (call, context) -> {
        delegateCalls.incrementAndGet();
        return Verdict.requireApproval("delegate");
      };

  private static Mandate mandate(RiskLabel maxRisk, Instant expiresAt, String... tools) {
    return Mandate.root("platform", "orchestrator")
        .principal("alice")
        .allowTools(tools)
        .maxRisk(maxRisk)
        .expiresAt(expiresAt)
        .build();
  }

  private static RunContext with(Mandate mandate) {
    return Mandates.attach(RunContext.builder().agentId("orchestrator").build(), mandate);
  }

  private static ToolCall call(String tool) {
    return ToolCall.of(tool, "{}");
  }

  @Test
  void deniesMissingMandateWhenRequired() {
    MandateGate gate = new MandateGate(resolver, true, countingDelegate, CLOCK);
    Verdict verdict = gate.check(call("crm.read"), RunContext.anonymous());
    assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.DENY);
    assertThat(verdict.reason()).isEqualTo(MandateGate.REASON_MISSING);
    assertThat(delegateCalls).hasValue(0);
  }

  @Test
  void passesMissingMandateToDelegateWhenOptional() {
    MandateGate gate = new MandateGate(resolver, false, countingDelegate, CLOCK);
    Verdict verdict = gate.check(call("crm.read"), RunContext.anonymous());
    assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.REQUIRE_APPROVAL);
    assertThat(verdict.reason()).isEqualTo("delegate");
    assertThat(delegateCalls).hasValue(1);
  }

  @Test
  void allowsWithoutDelegateWhenMandatePermits() {
    MandateGate gate = new MandateGate(resolver, true, null, CLOCK);
    Mandate m = mandate(RiskLabel.MEDIUM, NOW.plusSeconds(60), "crm.*");
    assertThat(gate.check(call("crm.read"), with(m)).allowed()).isTrue();
  }

  @Test
  void deniesExpiredMandate() {
    MandateGate gate = new MandateGate(resolver, true, countingDelegate, CLOCK);
    Mandate m = mandate(RiskLabel.HIGH, NOW.minusSeconds(1), "crm.*");
    Verdict verdict = gate.check(call("crm.read"), with(m));
    assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.DENY);
    assertThat(verdict.reason()).isEqualTo(MandateGate.REASON_EXPIRED + ":" + m.id());
    assertThat(delegateCalls).hasValue(0);
  }

  @Test
  void deniesToolOutsideMandateScope() {
    MandateGate gate = new MandateGate(resolver, true, countingDelegate, CLOCK);
    Mandate m = mandate(RiskLabel.HIGH, NOW.plusSeconds(60), "crm.*");
    Verdict verdict = gate.check(call("jira.issue.create"), with(m));
    assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.DENY);
    assertThat(verdict.reason()).isEqualTo(MandateGate.REASON_TOOL_NOT_PERMITTED + ":" + m.id());
    assertThat(delegateCalls).hasValue(0);
  }

  @Test
  void deniesToolRiskierThanMandateCeiling() {
    MandateGate gate = new MandateGate(resolver, true, countingDelegate, CLOCK);
    Mandate m = mandate(RiskLabel.MEDIUM, NOW.plusSeconds(60), "crm.*");
    Verdict verdict = gate.check(call("crm.delete"), with(m));
    assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.DENY);
    assertThat(verdict.reason()).isEqualTo(MandateGate.REASON_RISK_EXCEEDED + ":" + m.id());
    assertThat(delegateCalls).hasValue(0);
  }

  @Test
  void unclassifiedToolUsesPolicyDefaultRisk() {
    MandateGate gate = new MandateGate(resolver, true, null, CLOCK);
    Mandate low = mandate(RiskLabel.LOW, NOW.plusSeconds(60), "unknown.*");
    Mandate medium = mandate(RiskLabel.MEDIUM, NOW.plusSeconds(60), "unknown.*");
    assertThat(gate.check(call("unknown.tool"), with(low)).reason())
        .startsWith(MandateGate.REASON_RISK_EXCEEDED);
    assertThat(gate.check(call("unknown.tool"), with(medium)).allowed()).isTrue();
  }

  @Test
  void defersToDelegateOnceMandateChecksPass() {
    MandateGate gate = new MandateGate(resolver, true, countingDelegate, CLOCK);
    Mandate m = mandate(RiskLabel.HIGH, NOW.plusSeconds(60), "crm.*");
    Verdict verdict = gate.check(call("crm.delete"), with(m));
    assertThat(verdict.reason()).isEqualTo("delegate");
    assertThat(delegateCalls).hasValue(1);
  }

  @Test
  void deniesMalformedMandateAttribute() {
    MandateGate gate = new MandateGate(resolver, false, countingDelegate, CLOCK);
    RunContext ctx = RunContext.builder().attribute(Mandates.ATTRIBUTE, "garbage").build();
    Verdict verdict = gate.check(call("crm.read"), ctx);
    assertThat(verdict.outcome()).isEqualTo(Verdict.Outcome.DENY);
    assertThat(verdict.reason()).isEqualTo(MandateGate.REASON_MALFORMED);
    assertThat(delegateCalls).hasValue(0);
  }

  @Test
  void derivedMandateAtAChildHopIsEnforcedTheSameWay() {
    MandateGate gate = new MandateGate(resolver, true, null, CLOCK);
    Mandate root = mandate(RiskLabel.HIGH, NOW.plusSeconds(60), "crm.*", "jira.*");
    Mandate child =
        root.derive("jira-agent", Set.of("jira.*"), RiskLabel.MEDIUM, NOW.plusSeconds(30));
    RunContext jira = Mandates.attach(RunContext.builder().agentId("jira-agent").build(), child);
    assertThat(gate.check(call("jira.issue.create"), jira).allowed()).isTrue();
    assertThat(gate.check(call("crm.read"), jira).reason())
        .isEqualTo(MandateGate.REASON_TOOL_NOT_PERMITTED + ":" + child.id());
  }

  @Test
  void aMandateCarriedByAnotherAgentIsDenied() {
    MandateGate gate = new MandateGate(resolver, true, null, CLOCK);
    Mandate root = mandate(RiskLabel.HIGH, NOW.plusSeconds(60), "crm.*", "jira.*");
    Mandate child =
        root.derive("jira-agent", Set.of("jira.*"), RiskLabel.MEDIUM, NOW.plusSeconds(30));
    // The orchestrator carries the mandate it issued to jira-agent.
    Verdict v = gate.check(call("jira.issue.create"), with(child));
    assertThat(v.outcome()).isEqualTo(Verdict.Outcome.DENY);
    assertThat(v.reason()).isEqualTo(MandateGate.REASON_SUBJECT_MISMATCH + ":" + child.id());
    // A context without an agent id cannot be checked for subject and passes.
    RunContext anonymous = Mandates.attach(RunContext.anonymous(), child);
    assertThat(gate.check(call("jira.issue.create"), anonymous).allowed()).isTrue();
  }

  @Test
  void aForgedOrTamperedRootIsDenied() {
    MandateGate gate = new MandateGate(resolver, true, null, CLOCK);
    Mandate real = mandate(RiskLabel.LOW, NOW.plusSeconds(60), "crm.read");
    Mandate widened =
        new Mandate(
            real.id(),
            real.issuer(),
            real.subject(),
            real.principal(),
            Set.of("*"),
            RiskLabel.CRITICAL,
            0,
            real.maxDepth(),
            real.expiresAt(),
            null,
            real.digest());
    Verdict v = gate.check(call("crm.delete"), with(widened));
    assertThat(v.outcome()).isEqualTo(Verdict.Outcome.DENY);
    assertThat(v.reason()).startsWith(MandateGate.REASON_DIGEST_MISMATCH + ":");
  }
}
