package io.vegaduta.agent.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.approval.ApprovalStatus;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.tool.Tool;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.api.tool.ToolDescriptor;
import io.vegaduta.agent.api.tool.ToolResult;
import io.vegaduta.agent.api.tool.ToolStatus;
import io.vegaduta.agent.core.Governance;
import io.vegaduta.agent.core.GovernanceMode;
import io.vegaduta.agent.core.GovernancePolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class JdbcStoresTest {

  private static DataSource dataSource;

  @BeforeAll
  static void schema() {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:vegaduta;DB_CLOSE_DELAY=-1");
    dataSource = ds;
    VegadutaAgentSchema.initialize(ds);
    VegadutaAgentSchema.initialize(ds);
  }

  @Test
  void detectsDialect() {
    assertThat(VegadutaAgentSchema.detect(dataSource)).isEqualTo(VegadutaAgentSchema.Dialect.H2);
  }

  @Test
  void approvalStoreRoundTripsAndFilters() {
    JdbcApprovalStore store = new JdbcApprovalStore(dataSource);
    RunContext ctx =
        RunContext.builder()
            .tenantId("t1")
            .principal("alice")
            .agentId("a1")
            .runId("run-" + UUID.randomUUID())
            .attribute("channel", "slack \"ops\"")
            .build();
    Instant now = Instant.parse("2026-10-12T10:00:00Z");
    ApprovalRequest pending =
        ApprovalRequest.pending(
            "tool:refund", "refund 42", "{\"order\":\"42\"}", ctx, now, now.plusSeconds(3600));

    store.save(pending);
    ApprovalRequest loaded = store.find(pending.id()).orElseThrow();
    assertThat(loaded).isEqualTo(pending);
    assertThat(loaded.context().attributes()).containsEntry("channel", "slack \"ops\"");
    assertThat(store.findPending("t1", 10)).extracting(ApprovalRequest::id).contains(pending.id());
    assertThat(store.findPending("other", 10))
        .extracting(ApprovalRequest::id)
        .doesNotContain(pending.id());
    assertThat(store.findByRun(ctx.runId())).hasSize(1);

    ApprovalRequest decided =
        pending
            .withDecision(ApprovalStatus.APPROVED, now.plusSeconds(5), "bob", "ok")
            .withResult(ApprovalStatus.EXECUTED, "done");
    store.save(decided);
    ApprovalRequest reloaded = store.find(pending.id()).orElseThrow();
    assertThat(reloaded.status()).isEqualTo(ApprovalStatus.EXECUTED);
    assertThat(reloaded.decidedBy()).isEqualTo("bob");
    assertThat(reloaded.resultJson()).isEqualTo("done");
    assertThat(store.findPending("t1", 10))
        .extracting(ApprovalRequest::id)
        .doesNotContain(pending.id());
  }

  @Test
  void effectJournalTracksStartedAndDoneWithTtl() {
    Instant start = Instant.parse("2026-10-12T10:00:00Z");
    MutableClock clock = new MutableClock(start);
    JdbcEffectJournal journal = new JdbcEffectJournal(dataSource, Duration.ofMinutes(10), clock);
    String key = "k-" + UUID.randomUUID();

    journal.begin(key);
    assertThat(journal.lookup(key)).isEmpty();
    assertThat(journal.findInterrupted(start.plusSeconds(1))).contains(key);
    journal.record(key, "result");
    assertThat(journal.lookup(key)).contains("result");
    assertThat(journal.findInterrupted(start.plusSeconds(1))).doesNotContain(key);

    clock.now = start.plus(Duration.ofMinutes(11));
    assertThat(journal.lookup(key)).isEmpty();
    assertThat(journal.purgeExpired()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void auditSinkWritesAndReadsBack() {
    JdbcAuditSink sink = new JdbcAuditSink(dataSource);
    RunContext ctx =
        RunContext.builder()
            .tenantId("t1")
            .principal("alice")
            .runId("run-" + UUID.randomUUID())
            .build();
    AuditEvent event =
        AuditEvent.builder("tool.call", "tool", "refund")
            .context(ctx)
            .outcome(AuditOutcome.PENDING_APPROVAL)
            .detail("waiting")
            .risks(Set.of(AsiRisk.ASI02, AsiRisk.ASI09))
            .attribute("approvalId", "x")
            .build();

    sink.emit(event);
    AuditEvent loaded = sink.findByRun(ctx.runId()).get(0);
    assertThat(loaded.id()).isEqualTo(event.id());
    assertThat(loaded.outcome()).isEqualTo(AuditOutcome.PENDING_APPROVAL);
    assertThat(loaded.risks()).containsExactly(AsiRisk.ASI02, AsiRisk.ASI09);
    assertThat(loaded.attributes()).containsEntry("approvalId", "x");
    assertThat(loaded.context().principal()).isEqualTo("alice");
  }

  @Test
  void governanceWorksEndToEndOnJdbcStores() {
    JdbcAuditSink audit = new JdbcAuditSink(dataSource);
    Governance governance =
        Governance.builder()
            .policy(GovernancePolicy.builder().mode(GovernanceMode.STRICT).build())
            .approvalStore(new JdbcApprovalStore(dataSource))
            .effectJournal(new JdbcEffectJournal(dataSource, Duration.ofMinutes(5)))
            .auditSink(audit)
            .classification(
                ToolClassification.builder("pay").requiresApproval(true).idempotent(true).build())
            .build();
    Tool pay =
        new Tool() {
          @Override
          public ToolDescriptor descriptor() {
            return ToolDescriptor.of("pay", "pay");
          }

          @Override
          public String call(String argumentsJson, RunContext context) {
            return "paid " + argumentsJson;
          }
        };
    RunContext ctx =
        RunContext.builder().principal("alice").runId("run-" + UUID.randomUUID()).build();

    ToolResult result = governance.govern(pay).invoke(ToolCall.of("pay", "{\"amt\":1}"), ctx);
    assertThat(result.status()).isEqualTo(ToolStatus.PENDING_APPROVAL);
    ApprovalRequest executed =
        governance.approvals().approve(UUID.fromString(result.approvalId()), "bob");
    assertThat(executed.status()).isEqualTo(ApprovalStatus.EXECUTED);
    assertThat(executed.resultJson()).isEqualTo("paid {\"amt\":1}");

    ToolResult replay = governance.govern(pay).invoke(ToolCall.of("pay", "{\"amt\":1}"), ctx);
    assertThat(replay.status())
        .as("the approved call's result is journaled; no second payment")
        .isEqualTo(ToolStatus.REPLAYED);
    assertThat(audit.findByRun(ctx.runId()))
        .extracting(AuditEvent::outcome)
        .contains(AuditOutcome.PENDING_APPROVAL, AuditOutcome.APPROVED, AuditOutcome.REPLAYED);
  }

  private static final class MutableClock extends Clock {
    Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

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
      return now;
    }
  }
}
