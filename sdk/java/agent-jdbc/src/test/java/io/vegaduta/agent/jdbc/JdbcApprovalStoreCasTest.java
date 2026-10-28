package io.vegaduta.agent.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.approval.ApprovalAlreadyDecidedException;
import io.vegaduta.agent.api.approval.ApprovalDecisionAuthorizer;
import io.vegaduta.agent.api.approval.ApprovalActionExecutor;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.approval.ApprovalStatus;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.core.approval.ApprovalActionExecutorRegistry;
import io.vegaduta.agent.core.approval.DefaultApprovalGate;
import io.vegaduta.agent.core.hook.HookEngine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link JdbcApprovalStore#saveIfStatus} is a real compare-and-set: separate connections racing on
 * one row produce exactly one winner, so two gates (or JVMs) on one database cannot both approve.
 */
class JdbcApprovalStoreCasTest {

  private static DataSource dataSource;

  @BeforeAll
  static void schema() {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:vegaduta_cas;DB_CLOSE_DELAY=-1");
    dataSource = ds;
    VegadutaAgentSchema.initialize(ds);
  }

  private static ApprovalRequest pending(String tenant) {
    Instant now = Instant.parse("2026-10-12T10:00:00Z");
    return ApprovalRequest.pending(
        "tool:pay",
        "pay 42",
        "{\"order\":\"42\"}",
        RunContext.builder().tenantId(tenant).principal("agent").runId("r-" + UUID.randomUUID()).build(),
        now,
        now.plusSeconds(3600));
  }

  @Test
  void savesOnlyWhileTheStoredStatusMatches() {
    JdbcApprovalStore store = new JdbcApprovalStore(dataSource);
    ApprovalRequest pending = pending("t-cas-1");
    store.save(pending);
    Instant at = Instant.parse("2026-10-12T10:05:00Z");

    ApprovalRequest approved = pending.withDecision(ApprovalStatus.APPROVED, at, "bob", "ok");
    // wrong expectation: refused, row untouched
    assertThat(store.saveIfStatus(approved, ApprovalStatus.APPROVED)).isFalse();
    assertThat(store.find(pending.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);

    assertThat(store.saveIfStatus(approved, ApprovalStatus.PENDING)).isTrue();
    ApprovalRequest stored = store.find(pending.id()).orElseThrow();
    assertThat(stored.status()).isEqualTo(ApprovalStatus.APPROVED);
    assertThat(stored.decidedBy()).isEqualTo("bob");
    assertThat(stored.decisionReason()).isEqualTo("ok");
    assertThat(stored.decidedAt()).isEqualTo(at);

    // the loser, who also saw PENDING, cannot overwrite the decision
    ApprovalRequest rejected = pending.withDecision(ApprovalStatus.REJECTED, at, "eve", "no");
    assertThat(store.saveIfStatus(rejected, ApprovalStatus.PENDING)).isFalse();
    assertThat(store.find(pending.id()).orElseThrow().decidedBy()).isEqualTo("bob");

    // the next legitimate step still works
    assertThat(
            store.saveIfStatus(
                approved.withResult(ApprovalStatus.EXECUTED, "{}"), ApprovalStatus.APPROVED))
        .isTrue();
    assertThat(store.find(pending.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.EXECUTED);
  }

  @Test
  void aMissingRowNeverMatches() {
    JdbcApprovalStore store = new JdbcApprovalStore(dataSource);
    assertThat(store.saveIfStatus(pending("t-cas-2"), ApprovalStatus.PENDING)).isFalse();
  }

  @Test
  void manyConnectionsRacingOnOneRowProduceOneWinner() throws Exception {
    JdbcApprovalStore store = new JdbcApprovalStore(dataSource);
    ApprovalRequest pending = pending("t-cas-3");
    store.save(pending);
    int contenders = 12;
    ExecutorService pool = Executors.newFixedThreadPool(contenders);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger winners = new AtomicInteger();
    for (int i = 0; i < contenders; i++) {
      ApprovalRequest decided =
          pending.withDecision(
              i % 2 == 0 ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED,
              Instant.parse("2026-10-12T10:05:00Z"),
              "u" + i,
              null);
      pool.submit(
          () -> {
            start.await();
            if (store.saveIfStatus(decided, ApprovalStatus.PENDING)) {
              winners.incrementAndGet();
            }
            return null;
          });
    }
    start.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
    assertThat(winners.get()).isEqualTo(1);
  }

  @Test
  void twoGatesOnOneDatabaseRunAnApprovedActionOnce() throws Exception {
    JdbcApprovalStore store = new JdbcApprovalStore(dataSource);
    AtomicInteger runs = new AtomicInteger();
    ApprovalActionExecutor executor =
        new ApprovalActionExecutor() {
          @Override
          public String actionType() {
            return "tool:pay";
          }

          @Override
          public String execute(ApprovalRequest request) {
            runs.incrementAndGet();
            return "{\"ok\":true}";
          }
        };
    int gates = 6;
    List<DefaultApprovalGate> all = new ArrayList<>();
    for (int i = 0; i < gates; i++) {
      ApprovalActionExecutorRegistry registry = new ApprovalActionExecutorRegistry();
      registry.register(executor);
      all.add(
          new DefaultApprovalGate(
              new JdbcApprovalStore(dataSource),
              ApprovalDecisionAuthorizer.allowAny(),
              registry,
              AuditSink.discard(),
              new HookEngine(),
              Duration.ofMinutes(10),
              Clock.systemUTC()));
    }
    ApprovalRequest request =
        all.get(0)
            .request(
                "tool:pay",
                "pay 42",
                "{}",
                RunContext.builder().tenantId("t-cas-4").principal("agent").build());

    ExecutorService pool = Executors.newFixedThreadPool(gates);
    CountDownLatch start = new CountDownLatch(1);
    List<String> winners = Collections.synchronizedList(new ArrayList<>());
    AtomicInteger losers = new AtomicInteger();
    for (int i = 0; i < gates; i++) {
      DefaultApprovalGate gate = all.get(i);
      String who = "u" + i;
      pool.submit(
          () -> {
            start.await();
            try {
              gate.approve(request.id(), who);
              winners.add(who);
            } catch (ApprovalAlreadyDecidedException e) {
              losers.incrementAndGet();
            }
            return null;
          });
    }
    start.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

    assertThat(winners).hasSize(1);
    assertThat(losers.get()).isEqualTo(gates - 1);
    assertThat(runs.get()).isEqualTo(1);
    assertThat(store.find(request.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.EXECUTED);
  }
}
