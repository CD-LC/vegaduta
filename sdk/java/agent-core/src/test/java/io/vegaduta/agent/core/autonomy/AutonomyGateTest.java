package io.vegaduta.agent.core.autonomy;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.api.autonomy.AutonomyLevel;
import io.vegaduta.agent.api.gate.ToolDispatchGate;
import io.vegaduta.agent.api.gate.Verdict;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.core.GovernancePolicy;
import io.vegaduta.agent.core.audit.InMemoryAuditSink;
import io.vegaduta.agent.core.classification.ClassificationResolver;
import io.vegaduta.agent.core.classification.StaticClassificationSource;
import io.vegaduta.agent.core.gate.PolicyToolDispatchGate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutonomyGateTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneOffset.UTC);

  private final InMemoryAuditSink audit = new InMemoryAuditSink();
  private final AutonomyRegistry registry =
      new AutonomyRegistry(AutonomyLevel.L3_ACT_BOUNDED, audit, CLOCK);
  private final ClassificationResolver classifications =
      new ClassificationResolver(
          new StaticClassificationSource(
              List.of(
                  ToolClassification.readOnly("search"),
                  ToolClassification.builder("update_ticket")
                      .risk(RiskLabel.MEDIUM)
                      .sideEffecting(true)
                      .build(),
                  ToolClassification.builder("send_email")
                      .risk(RiskLabel.HIGH)
                      .sideEffecting(true)
                      .build(),
                  ToolClassification.builder("wire_funds")
                      .risk(RiskLabel.CRITICAL)
                      .sideEffecting(true)
                      .build(),
                  ToolClassification.builder("delete_tenant")
                      .risk(RiskLabel.CRITICAL)
                      .sideEffecting(true)
                      .requiresApproval(true)
                      .build())),
          GovernancePolicy.defaults());
  private final AutonomyGate gate =
      new AutonomyGate(new AutonomyResolver(registry), classifications);

  private static RunContext agent(String agentId) {
    return RunContext.builder().agentId(agentId).runId("run-1").build();
  }

  private static ToolCall call(String tool) {
    return ToolCall.of(tool, "{}");
  }

  @Test
  void observeLevelDeniesSideEffectsWithALevelComparisonReason() {
    registry.set("reader", AutonomyLevel.L1_OBSERVE, "ops", "read-only agent");

    assertThat(gate.check(call("search"), agent("reader"))).isEqualTo(Verdict.allow());
    Verdict v = gate.check(call("update_ticket"), agent("reader"));
    assertThat(v.outcome()).isEqualTo(Verdict.Outcome.DENY);
    assertThat(v.reason()).isEqualTo("autonomy:L1_OBSERVE<L3_ACT_BOUNDED");
  }

  @Test
  void recommendLevelEscalatesSideEffectsToApproval() {
    registry.set("drafter", AutonomyLevel.L2_RECOMMEND, "ops", "pilot");

    assertThat(gate.check(call("search"), agent("drafter"))).isEqualTo(Verdict.allow());
    assertThat(gate.check(call("update_ticket"), agent("drafter")))
        .isEqualTo(Verdict.requireApproval(AutonomyGate.REASON_RECOMMEND));
    assertThat(gate.check(call("wire_funds"), agent("drafter")))
        .isEqualTo(Verdict.requireApproval(AutonomyGate.REASON_RECOMMEND));
  }

  @Test
  void boundedLevelActsOnMediumNeedsApprovalOnHighAndCannotReachCritical() {
    RunContext ctx = agent("bounded");

    assertThat(gate.check(call("update_ticket"), ctx)).isEqualTo(Verdict.allow());
    assertThat(gate.check(call("send_email"), ctx))
        .isEqualTo(Verdict.requireApproval(AutonomyGate.REASON_BOUNDED_HIGH_RISK));
    Verdict critical = gate.check(call("wire_funds"), ctx);
    assertThat(critical.outcome()).isEqualTo(Verdict.Outcome.DENY);
    assertThat(critical.reason()).isEqualTo("autonomy:L3_ACT_BOUNDED<L4_ACT_AUTONOMOUS");
    assertThat(gate.check(call("delete_tenant"), ctx))
        .isEqualTo(Verdict.requireApproval(AutonomyGate.REASON_REQUIRES_APPROVAL));
  }

  @Test
  void autonomousLevelExecutesHighRiskButStillHonoursRequiresApproval() {
    registry.set("auto", AutonomyLevel.L4_ACT_AUTONOMOUS, "ops", "trusted");

    assertThat(gate.check(call("send_email"), agent("auto"))).isEqualTo(Verdict.allow());
    // CRITICAL is always approval-gated, even without the requiresApproval flag.
    assertThat(gate.check(call("wire_funds"), agent("auto")))
        .isEqualTo(Verdict.requireApproval(AutonomyGate.REASON_REQUIRES_APPROVAL));
    assertThat(gate.check(call("delete_tenant"), agent("auto")))
        .isEqualTo(Verdict.requireApproval(AutonomyGate.REASON_REQUIRES_APPROVAL));
  }

  @Test
  void demotionTakesEffectOnTheNextCallAndIsAudited() {
    registry.set("refunds", AutonomyLevel.L4_ACT_AUTONOMOUS, "ops", "launch");
    audit.clear();
    assertThat(gate.check(call("send_email"), agent("refunds"))).isEqualTo(Verdict.allow());

    assertThat(registry.demote("refunds", AutonomyLevel.L2_RECOMMEND, "oncall", "INC-4711"))
        .isTrue();

    assertThat(gate.check(call("send_email"), agent("refunds")))
        .isEqualTo(Verdict.requireApproval(AutonomyGate.REASON_RECOMMEND));
    assertThat(audit.events()).hasSize(1);
    AuditEvent event = audit.events().get(0);
    assertThat(event.actionType()).isEqualTo(AutonomyRegistry.ACTION_TYPE);
    assertThat(event.targetType()).isEqualTo("agent");
    assertThat(event.targetId()).isEqualTo("refunds");
    assertThat(event.actorId()).isEqualTo("oncall");
    assertThat(event.detail()).isEqualTo("INC-4711");
    assertThat(event.createdAt()).isEqualTo(CLOCK.instant());
    assertThat(event.attributes())
        .containsEntry("autonomy.from", "L4_ACT_AUTONOMOUS")
        .containsEntry("autonomy.to", "L2_RECOMMEND")
        .containsEntry("autonomy.change", "demote");
  }

  @Test
  void demoteNeverRaisesAndPromoteNeverLowers() {
    registry.set("a", AutonomyLevel.L2_RECOMMEND, "ops", "start");
    audit.clear();

    assertThat(registry.demote("a", AutonomyLevel.L4_ACT_AUTONOMOUS, "x", "oops")).isFalse();
    assertThat(registry.promote("a", AutonomyLevel.L1_OBSERVE, "x", "oops")).isFalse();
    assertThat(registry.levelOf("a")).isEqualTo(AutonomyLevel.L2_RECOMMEND);
    assertThat(audit.events()).isEmpty();

    assertThat(registry.promote("a", AutonomyLevel.L3_ACT_BOUNDED, "lead", "graduated")).isTrue();
    assertThat(audit.events()).hasSize(1);
    assertThat(audit.events().get(0).attributes()).containsEntry("autonomy.change", "promote");
  }

  @Test
  void runAttributeIsUsedWhenTheAgentIsNotRegistered() {
    RunContext pinned =
        RunContext.builder()
            .agentId("unknown")
            .attribute(AutonomyResolver.ATTRIBUTE, "L1_OBSERVE")
            .build();
    Verdict v = gate.check(call("update_ticket"), pinned);
    assertThat(v.outcome()).isEqualTo(Verdict.Outcome.DENY);
    assertThat(v.reason()).startsWith("autonomy:L1_OBSERVE<");

    // A malformed attribute fails closed to the lowest level and is audited, never to the default.
    audit.clear();
    RunContext malformed =
        RunContext.builder().attribute(AutonomyResolver.ATTRIBUTE, "L1-OBSERVE\nforged").build();
    assertThat(new AutonomyResolver(registry).resolve(malformed))
        .isEqualTo(AutonomyLevel.L1_OBSERVE);
    assertThat(audit.events()).hasSize(1);
    AuditEvent invalid = audit.events().get(0);
    assertThat(invalid.actionType()).isEqualTo(AutonomyRegistry.INVALID_ATTRIBUTE_ACTION_TYPE);
    assertThat(invalid.attributes().get("autonomy.attribute")).doesNotContain("\n");
  }

  @Test
  void demoteAndPromoteAreAtomicAgainstConcurrentSets() throws Exception {
    // demote never raises: a concurrent set() to a lower level must not be overwritten.
    registry.set("refunds", AutonomyLevel.L4_ACT_AUTONOMOUS, "ops", "launch");
    registry.set("refunds", AutonomyLevel.L1_OBSERVE, "automation", "incident");
    assertThat(registry.demote("refunds", AutonomyLevel.L3_ACT_BOUNDED, "oncall", "INC")).isFalse();
    assertThat(registry.levelOf("refunds")).isEqualTo(AutonomyLevel.L1_OBSERVE);

    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(8);
    try {
      java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
      for (int i = 0; i < 200; i++) {
        final int n = i;
        futures.add(
            pool.submit(
                () -> {
                  if (n % 2 == 0) {
                    registry.demote("race", AutonomyLevel.L1_OBSERVE, "a", "r");
                  } else {
                    registry.set("race", AutonomyLevel.L2_RECOMMEND, "b", "r");
                  }
                }));
      }
      for (java.util.concurrent.Future<?> f : futures) {
        f.get();
      }
    } finally {
      pool.shutdownNow();
    }
    // Every emitted "demote" event must really have lowered the level.
    for (AuditEvent e : audit.events()) {
      if (!"race".equals(e.targetId())) {
        continue;
      }
      AutonomyLevel from = AutonomyLevel.valueOf(e.attributes().get("autonomy.from"));
      AutonomyLevel to = AutonomyLevel.valueOf(e.attributes().get("autonomy.to"));
      if ("demote".equals(e.attributes().get("autonomy.change"))) {
        assertThat(to.below(from)).isTrue();
      } else {
        assertThat(from.below(to)).isTrue();
      }
    }
  }

  @Test
  void promotionIsRolledBackWhenTheAuditSinkRejectsIt() {
    AuditSink broken =
        event -> {
          throw new IllegalStateException("sink down");
        };
    AutonomyRegistry r = new AutonomyRegistry(AutonomyLevel.L3_ACT_BOUNDED, broken, CLOCK);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> r.promote("payments", AutonomyLevel.L4_ACT_AUTONOMOUS, "ops", "go"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(r.levelOf("payments")).isEqualTo(AutonomyLevel.L3_ACT_BOUNDED);
    assertThat(r.get("payments")).isEmpty();
  }

  @Test
  void registeredLevelWinsOverTheRunAttribute() {
    registry.set("demoted", AutonomyLevel.L1_OBSERVE, "ops", "incident");
    RunContext stale =
        RunContext.builder()
            .agentId("demoted")
            .attribute(AutonomyResolver.ATTRIBUTE, "L4_ACT_AUTONOMOUS")
            .build();
    assertThat(gate.check(call("update_ticket"), stale).outcome()).isEqualTo(Verdict.Outcome.DENY);
  }

  @Test
  void composesWithTheDelegateDenyWinsThenApprovalThenAllow() {
    registry.set("auto", AutonomyLevel.L4_ACT_AUTONOMOUS, "ops", "trusted");
    ToolDispatchGate policy =
        new PolicyToolDispatchGate(classifications).allow("search", "update_ticket");
    AutonomyGate composed =
        new AutonomyGate(new AutonomyResolver(registry), classifications, policy);

    // Autonomy allows send_email at L4, the policy allow-list does not: DENY wins.
    assertThat(composed.check(call("send_email"), agent("auto")))
        .isEqualTo(Verdict.deny(PolicyToolDispatchGate.REASON_NOT_ALLOW_LISTED));
    // Both allow.
    assertThat(composed.check(call("update_ticket"), agent("auto"))).isEqualTo(Verdict.allow());
    // Autonomy asks for approval, the policy allows: approval.
    registry.demote("auto", AutonomyLevel.L2_RECOMMEND, "oncall", "INC-1");
    assertThat(composed.check(call("update_ticket"), agent("auto")))
        .isEqualTo(Verdict.requireApproval(AutonomyGate.REASON_RECOMMEND));
  }

  @Test
  void unclassifiedToolsGetThePolicyDefaultClassification() {
    // Default policy: MEDIUM, side-effecting -> L3 minimum; our default registry level is L3.
    assertThat(gate.check(call("mystery"), agent("anyone"))).isEqualTo(Verdict.allow());
    registry.set("anyone", AutonomyLevel.L1_OBSERVE, "ops", "lock down");
    assertThat(gate.check(call("mystery"), agent("anyone")).outcome())
        .isEqualTo(Verdict.Outcome.DENY);
  }

  @Test
  void auditSinkFailuresDoNotBreakTheChange() {
    AuditSink broken =
        event -> {
          throw new IllegalStateException("sink down");
        };
    AutonomyRegistry r = new AutonomyRegistry(AutonomyLevel.L3_ACT_BOUNDED, broken, CLOCK);
    assertThat(r.demote("x", AutonomyLevel.L1_OBSERVE, "ops", "now")).isTrue();
    assertThat(r.levelOf("x")).isEqualTo(AutonomyLevel.L1_OBSERVE);
  }
}
