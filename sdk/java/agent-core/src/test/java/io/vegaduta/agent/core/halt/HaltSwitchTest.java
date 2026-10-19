package io.vegaduta.agent.core.halt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.core.audit.InMemoryAuditSink;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class HaltSwitchTest {

  private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");

  private final InMemoryAuditSink audit = new InMemoryAuditSink();
  private final HaltSwitch halt = new HaltSwitch(audit, Clock.fixed(T0, ZoneOffset.UTC));

  private static RunContext ctx(String tenant, String agent, String run) {
    return RunContext.builder().tenantId(tenant).agentId(agent).runId(run).build();
  }

  @Test
  void scopeMatchingAndValidation() {
    RunContext ctx = ctx("t1", "a1", "r1");
    assertThat(HaltScope.global().matches(ctx)).isTrue();
    assertThat(HaltScope.tenant("t1").matches(ctx)).isTrue();
    assertThat(HaltScope.tenant("t2").matches(ctx)).isFalse();
    assertThat(HaltScope.agent("a1").matches(ctx)).isTrue();
    assertThat(HaltScope.agent("a1").matches(ctx("t1", null, "r1"))).isFalse();
    assertThat(HaltScope.run("r1").matches(ctx)).isTrue();
    assertThat(HaltScope.run("r2").matches(ctx)).isFalse();
    assertThatThrownBy(() -> HaltScope.agent(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new HaltScope(HaltScope.Kind.GLOBAL, "x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void mostSpecificActiveHaltWins() {
    RunContext ctx = ctx("t1", "a1", "r1");
    HaltRecord global = halt.engage(HaltScope.global(), "ops", "everything");
    HaltRecord tenant = halt.engage(HaltScope.tenant("t1"), "ops", "tenant");
    HaltRecord agent = halt.engage(HaltScope.agent("a1"), "ops", "agent");
    HaltRecord otherRun = halt.engage(HaltScope.run("r9"), "ops", "another run");

    assertThat(halt.isHalted(ctx)).contains(agent);
    assertThat(halt.isHalted(ctx("t1", "a2", "r2"))).contains(tenant);
    assertThat(halt.isHalted(ctx("t2", null, "r3"))).contains(global);
    assertThat(halt.active()).containsExactly(otherRun, agent, tenant, global);

    HaltRecord run = halt.engage(HaltScope.run("r1"), "ops", "this run");
    assertThat(halt.isHalted(ctx)).contains(run);

    halt.release(run.id(), "ops");
    halt.release(agent.id(), "ops");
    assertThat(halt.isHalted(ctx)).contains(tenant);
    halt.release(tenant.id(), "ops");
    halt.release(global.id(), "ops");
    assertThat(halt.isHalted(ctx)).isEmpty();
    assertThat(halt.isHalted(ctx("t1", "a1", "r9"))).contains(otherRun);
  }

  @Test
  void engageAndReleaseEmitAuditEvents() {
    HaltRecord record = halt.engage(HaltScope.agent("a1"), "alice", "incident 42");
    assertThat(record.at()).isEqualTo(T0);
    assertThat(halt.release(record.id(), "bob")).contains(record);
    assertThat(halt.release(record.id(), "bob")).isEmpty();

    List<AuditEvent> events = audit.events();
    assertThat(events).hasSize(2);
    AuditEvent engaged = events.get(0);
    assertThat(engaged.actionType()).isEqualTo("halt.engaged");
    assertThat(engaged.targetType()).isEqualTo("halt");
    assertThat(engaged.targetId()).isEqualTo(record.id());
    assertThat(engaged.actorId()).isEqualTo("alice");
    assertThat(engaged.detail()).isEqualTo("incident 42");
    assertThat(engaged.outcome()).isEqualTo(AuditOutcome.SUCCESS);
    assertThat(engaged.createdAt()).isEqualTo(T0);
    assertThat(engaged.risks()).containsExactlyInAnyOrder(AsiRisk.ASI08, AsiRisk.ASI09);
    assertThat(engaged.context().agentId()).isEqualTo("a1");
    assertThat(engaged.attributes()).containsEntry("scope", "AGENT(a1)");

    AuditEvent released = events.get(1);
    assertThat(released.actionType()).isEqualTo("halt.released");
    assertThat(released.actorId()).isEqualTo("bob");
    assertThat(released.attributes()).containsEntry("engagedBy", "alice");
    assertThat(released.risks()).containsExactlyInAnyOrder(AsiRisk.ASI08, AsiRisk.ASI09);
  }

  @Test
  void auditSinkFailureDoesNotBreakTheSwitch() {
    HaltSwitch fragile =
        new HaltSwitch(
            event -> {
              throw new IllegalStateException("sink down");
            });
    HaltRecord record = fragile.engage(HaltScope.global(), "ops", "x");
    assertThat(fragile.isHalted(RunContext.anonymous())).contains(record);
    assertThat(fragile.release(record.id(), "ops")).contains(record);
  }
}
