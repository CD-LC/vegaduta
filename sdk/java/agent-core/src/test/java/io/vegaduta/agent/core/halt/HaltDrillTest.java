package io.vegaduta.agent.core.halt;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.tool.Tool;
import io.vegaduta.agent.api.tool.ToolDescriptor;
import io.vegaduta.agent.api.tool.ToolStatus;
import io.vegaduta.agent.core.Governance;
import io.vegaduta.agent.core.GovernanceMode;
import io.vegaduta.agent.core.GovernancePolicy;
import io.vegaduta.agent.core.GovernedTool;
import io.vegaduta.agent.core.audit.InMemoryAuditSink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class HaltDrillTest {

  private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");

  private final InMemoryAuditSink audit = new InMemoryAuditSink();
  private final AtomicInteger probeCalls = new AtomicInteger();
  private final RunContext ctx = RunContext.builder().principal("alice").agentId("a1").build();

  /** A clock that advances 7 ms on every read, so durations are deterministic and non-zero. */
  private final Clock clock =
      new Clock() {
        private final AtomicLong ticks = new AtomicLong();

        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return T0.plus(Duration.ofMillis(7L * ticks.getAndIncrement()));
        }
      };

  private Tool probe() {
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return ToolDescriptor.of("probe", "no-op drill probe");
      }

      @Override
      public String call(String argumentsJson, RunContext context) {
        probeCalls.incrementAndGet();
        return "pong";
      }
    };
  }

  private Governance.Builder governance() {
    return Governance.builder()
        .policy(GovernancePolicy.builder().mode(GovernanceMode.STRICT).build())
        .classification(ToolClassification.readOnly("probe"))
        .auditSink(audit);
  }

  @Test
  void drillPassesWhenHaltGateIsWired() {
    HaltSwitch halt = new HaltSwitch(audit, clock);
    Governance governance = governance().gate(new HaltGate(halt)).build();
    GovernedTool tool = governance.govern(probe());

    DrillReport report = new HaltDrill(halt).run(tool, ctx, "auditor");

    assertThat(report.passed()).isTrue();
    assertThat(report.deniedWhileHalted()).isTrue();
    assertThat(report.resumedAfterRelease()).isTrue();
    assertThat(report.scope()).isEqualTo(HaltScope.agent("a1"));
    assertThat(report.haltedResult().status()).isEqualTo(ToolStatus.DENIED);
    assertThat(report.haltedResult().reason()).startsWith("halted:" + report.haltId() + ":");
    assertThat(report.resumedResult().status()).isEqualTo(ToolStatus.OK);
    assertThat(report.millis()).isGreaterThan(0);
    assertThat(probeCalls).hasValue(1);
    assertThat(halt.active()).isEmpty();
    assertThat(report.toMarkdown())
        .startsWith("## Halt drill PASSED")
        .contains("| Denied while halted | yes |")
        .contains("| Resumed after release | yes |");

    List<AuditEvent> drills =
        audit.events().stream().filter(e -> e.actionType().equals("halt.drill")).toList();
    assertThat(drills).hasSize(1);
    AuditEvent event = drills.get(0);
    assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
    assertThat(event.targetType()).isEqualTo("halt");
    assertThat(event.targetId()).isEqualTo(report.id());
    assertThat(event.actorId()).isEqualTo("auditor");
    assertThat(event.risks()).containsExactlyInAnyOrder(AsiRisk.ASI08, AsiRisk.ASI09);
    assertThat(event.attributes())
        .containsEntry("deniedWhileHalted", "true")
        .containsEntry("resumedAfterRelease", "true")
        .containsEntry("millis", Long.toString(report.millis()))
        .containsEntry("haltId", report.haltId());

    List<String> actions = audit.events().stream().map(AuditEvent::actionType).toList();
    assertThat(actions)
        .containsSubsequence(
            "halt.engaged", "tool.call", "halt.released", "tool.call", "halt.drill");
  }

  @Test
  void drillFailsWhenHaltGateIsNotWired() {
    HaltSwitch halt = new HaltSwitch(audit, clock);
    Governance governance = governance().build();
    GovernedTool tool = governance.govern(probe());

    DrillReport report = new HaltDrill(halt).run(tool, ctx, "auditor");

    assertThat(report.passed()).isFalse();
    assertThat(report.deniedWhileHalted()).isFalse();
    assertThat(report.resumedAfterRelease()).isTrue();
    assertThat(report.haltedResult().status())
        .as("without the gate the probe ran while halted")
        .isEqualTo(ToolStatus.OK);
    assertThat(report.resumedResult().status())
        .as("the identical post-release call is replayed from the effect journal")
        .isEqualTo(ToolStatus.REPLAYED);
    assertThat(probeCalls).hasValue(1);
    assertThat(halt.active()).as("the drill releases its halt even on failure").isEmpty();
    assertThat(report.toMarkdown()).startsWith("## Halt drill FAILED").contains("HaltGate");

    AuditEvent event =
        audit.events().stream().filter(e -> e.actionType().equals("halt.drill")).findFirst().get();
    assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
    assertThat(event.attributes()).containsEntry("deniedWhileHalted", "false");
  }

  @Test
  void drillFallsBackToRunScopeWithoutAgentId() {
    HaltSwitch halt = new HaltSwitch(audit, clock);
    Governance governance = governance().gate(new HaltGate(halt)).build();
    GovernedTool tool = governance.govern(probe());
    RunContext noAgent = RunContext.ofRun("run-7");

    DrillReport report = new HaltDrill(halt).run(tool, noAgent, "auditor");

    assertThat(report.passed()).isTrue();
    // The drill runs under a per-drill run id derived from the caller's run.
    assertThat(report.scope().kind()).isEqualTo(HaltScope.Kind.RUN);
    assertThat(report.scope().id()).startsWith("run-7-drill-");
  }

  @Test
  void repeatedDrillsInOneRunBothPass() {
    HaltSwitch halt = new HaltSwitch(audit, clock);
    Governance governance = governance().gate(new HaltGate(halt)).build();
    GovernedTool tool = governance.govern(probe());

    DrillReport first = new HaltDrill(halt).run(tool, ctx, "auditor");
    DrillReport second = new HaltDrill(halt).run(tool, ctx, "auditor");

    assertThat(first.passed()).isTrue();
    assertThat(second.passed())
        .as("the second drill's probe must not be served from the journal before the gate")
        .isTrue();
    assertThat(second.haltedResult().status()).isEqualTo(ToolStatus.DENIED);
  }
}
