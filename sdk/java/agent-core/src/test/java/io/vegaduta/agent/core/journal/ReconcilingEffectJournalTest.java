package io.vegaduta.agent.core.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.core.audit.InMemoryAuditSink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReconcilingEffectJournalTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
  private final InMemoryEffectJournal delegate =
      new InMemoryEffectJournal(Duration.ofHours(1), 1000, clock);
  private final InMemoryAuditSink audit = new InMemoryAuditSink();
  private final List<ReconcileContext> probed = new ArrayList<>();

  private ReconcilingEffectJournal journal(ReconcileOutcome answer, UnknownEffectPolicy policy) {
    return ReconcilingEffectJournal.wrap(delegate)
        .reconciler(
            (key, ctx) -> {
              probed.add(ctx);
              return answer;
            })
        .auditSink(audit)
        .clock(clock)
        .unknownPolicy(policy)
        .build();
  }

  @Test
  void trackerReportsBegunButNotRecordedKeysOlderThanThreshold() {
    InterruptedCallTracker tracker = new InterruptedCallTracker();
    tracker.begin("old", T0.minusSeconds(600));
    tracker.begin("fresh", T0.minusSeconds(5));
    tracker.begin("done", T0.minusSeconds(900));
    tracker.record("done");

    List<InterruptedCallTracker.InterruptedCall> interrupted =
        tracker.interrupted(Duration.ofSeconds(60), T0);

    assertThat(interrupted)
        .extracting(InterruptedCallTracker.InterruptedCall::key)
        .containsExactly("old");
    assertThat(tracker.isInterrupted("fresh")).isTrue();
    assertThat(tracker.isInterrupted("done")).isFalse();
    assertThat(tracker.size()).isEqualTo(2);
  }

  @Test
  void completedCallsFlowThroughWithoutProbing() {
    ReconcilingEffectJournal journal =
        journal(ReconcileOutcome.unknown("should not be asked"), UnknownEffectPolicy.HOLD);
    journal.begin("k1");
    journal.record("k1", "posted");

    assertThat(journal.lookup("k1")).contains("posted");
    assertThat(journal.lookup("never-begun")).isEmpty();
    assertThat(probed).isEmpty();
    assertThat(audit.events()).isEmpty();
  }

  @Test
  void completedOutcomeRecordsResultAndLaterLookupsReplay() {
    ReconcilingEffectJournal journal =
        journal(ReconcileOutcome.completed("txn-42"), UnknownEffectPolicy.RETRY);
    journal.describe("k1", RunContext.ofRun("run-1"), "post_payment", "{\"amount\":10}");
    journal.begin("k1");
    // crash here: record never happens

    assertThat(journal.lookup("k1")).contains("txn-42");
    assertThat(delegate.lookup("k1")).contains("txn-42");
    assertThat(journal.tracker().isInterrupted("k1")).isFalse();

    assertThat(probed).hasSize(1);
    ReconcileContext ctx = probed.get(0);
    assertThat(ctx.key()).isEqualTo("k1");
    assertThat(ctx.since()).isEqualTo(T0);
    assertThat(ctx.toolName()).isEqualTo("post_payment");
    assertThat(ctx.runId()).isEqualTo("run-1");
    assertThat(ctx.described()).isTrue();

    assertThat(journal.lookup("k1")).contains("txn-42");
    assertThat(probed).hasSize(1);

    assertThat(audit.events()).hasSize(1);
    AuditEvent event = audit.events().get(0);
    assertThat(event.actionType()).isEqualTo(ReconcilingEffectJournal.ACTION_RECONCILED);
    assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
    assertThat(event.detail()).isEqualTo("completed");
    assertThat(event.targetId()).isEqualTo("k1");
    assertThat(event.risks()).containsExactly(AsiRisk.ASI08);
    assertThat(event.context().runId()).isEqualTo("run-1");
    assertThat(event.attributes()).containsEntry("toolName", "post_payment");
  }

  @Test
  void notPerformedClearsInterruptedMarkAndLetsTheCallRun() {
    ReconcilingEffectJournal journal =
        journal(ReconcileOutcome.notPerformed(), UnknownEffectPolicy.HOLD);
    journal.begin("k1");

    assertThat(journal.lookup("k1")).isEmpty();
    assertThat(journal.tracker().isInterrupted("k1")).isFalse();
    assertThat(probed).hasSize(1);

    assertThat(journal.lookup("k1")).isEmpty();
    assertThat(probed).hasSize(1);

    assertThat(audit.events()).hasSize(1);
    AuditEvent event = audit.events().get(0);
    assertThat(event.actionType()).isEqualTo(ReconcilingEffectJournal.ACTION_RECONCILED);
    assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
    assertThat(event.detail()).isEqualTo("not-performed");
  }

  @Test
  void unknownUnderRetryWarnsAndReturnsEmptyButStaysInterrupted() {
    ReconcilingEffectJournal journal =
        journal(ReconcileOutcome.unknown("gateway timeout"), UnknownEffectPolicy.RETRY);
    journal.begin("k1");

    assertThat(journal.lookup("k1")).isEmpty();
    assertThat(journal.tracker().isInterrupted("k1")).isTrue();

    assertThat(audit.events()).hasSize(1);
    AuditEvent event = audit.events().get(0);
    assertThat(event.actionType()).isEqualTo(ReconcilingEffectJournal.ACTION_UNRESOLVED);
    assertThat(event.outcome()).isEqualTo(AuditOutcome.WARNING);
    assertThat(event.detail()).isEqualTo("gateway timeout");
    assertThat(event.attributes()).containsEntry("unknownPolicy", "RETRY");
  }

  @Test
  void unknownUnderHoldThrowsEffectUnresolved() {
    ReconcilingEffectJournal journal =
        journal(ReconcileOutcome.unknown("gateway timeout"), UnknownEffectPolicy.HOLD);
    journal.begin("k1");

    assertThatThrownBy(() -> journal.lookup("k1"))
        .isInstanceOf(EffectUnresolvedException.class)
        .satisfies(
            e -> {
              EffectUnresolvedException unresolved = (EffectUnresolvedException) e;
              assertThat(unresolved.key()).isEqualTo("k1");
              assertThat(unresolved.since()).isEqualTo(T0);
              assertThat(unresolved.reason()).isEqualTo("gateway timeout");
            });
    assertThat(journal.tracker().isInterrupted("k1")).isTrue();
    assertThat(audit.events())
        .extracting(AuditEvent::outcome)
        .containsExactly(AuditOutcome.WARNING);

    journal.resolve("k1", "txn-manual");
    assertThat(journal.lookup("k1")).contains("txn-manual");
  }

  @Test
  void reconcilerExceptionsAreTreatedAsUnknown() {
    ReconcilingEffectJournal journal =
        ReconcilingEffectJournal.wrap(delegate)
            .reconciler(
                (key, ctx) -> {
                  throw new IllegalStateException("ERP down");
                })
            .auditSink(audit)
            .clock(clock)
            .holdOnUnknown(true)
            .build();
    journal.begin("k1");

    assertThatThrownBy(() -> journal.lookup("k1"))
        .isInstanceOf(EffectUnresolvedException.class)
        .hasMessageContaining("ERP down");
  }

  @Test
  void sweepReconcilesEveryInterruptedKeyOlderThanThreshold() {
    ReconcilingEffectJournal journal =
        ReconcilingEffectJournal.wrap(delegate)
            .reconciler(
                (key, ctx) ->
                    switch (key) {
                      case "done" -> ReconcileOutcome.completed("r-done");
                      case "never" -> ReconcileOutcome.notPerformed();
                      default -> ReconcileOutcome.unknown("?");
                    })
            .auditSink(audit)
            .clock(clock)
            .holdOnUnknown(true)
            .build();
    journal.tracker().seed("done", T0.minusSeconds(300));
    journal.tracker().seed("never", T0.minusSeconds(200));
    journal.tracker().seed("mystery", T0.minusSeconds(100));
    journal.tracker().seed("fresh", T0.minusSeconds(1));

    List<ReconcilingEffectJournal.SweepResult> results =
        journal.reconcileAll(Duration.ofSeconds(60));

    assertThat(results)
        .extracting(ReconcilingEffectJournal.SweepResult::key)
        .containsExactly("done", "never", "mystery");
    assertThat(results.get(0).outcome()).isEqualTo(ReconcileOutcome.completed("r-done"));
    assertThat(results.get(1).outcome()).isEqualTo(ReconcileOutcome.notPerformed());
    assertThat(results.get(2).outcome()).isInstanceOf(ReconcileOutcome.Unknown.class);

    assertThat(delegate.lookup("done")).contains("r-done");
    assertThat(journal.tracker().isInterrupted("done")).isFalse();
    assertThat(journal.tracker().isInterrupted("never")).isFalse();
    assertThat(journal.tracker().isInterrupted("mystery")).isTrue();
    assertThat(journal.tracker().isInterrupted("fresh")).isTrue();
    assertThat(audit.events())
        .extracting(AuditEvent::actionType)
        .containsExactly(
            ReconcilingEffectJournal.ACTION_RECONCILED,
            ReconcilingEffectJournal.ACTION_RECONCILED,
            ReconcilingEffectJournal.ACTION_UNRESOLVED);
  }

  @Test
  void concurrentLookupsOfOneInterruptedKeyProbeOnce() throws Exception {
    AtomicInteger probes = new AtomicInteger();
    ReconcilingEffectJournal journal =
        ReconcilingEffectJournal.wrap(delegate)
            .reconciler(
                (key, ctx) -> {
                  probes.incrementAndGet();
                  return ReconcileOutcome.completed("once");
                })
            .auditSink(audit)
            .clock(clock)
            .build();
    journal.begin("k1");

    int threads = 16;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<String>> futures = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      futures.add(
          pool.submit(
              () -> {
                go.await();
                return journal.lookup("k1").orElse("missing");
              }));
    }
    go.countDown();
    for (Future<String> f : futures) {
      assertThat(f.get()).isEqualTo("once");
    }
    pool.shutdown();

    assertThat(probes).hasValue(1);
    assertThat(audit.events()).hasSize(1);
  }

  @Test
  void concurrentLookupsOfAnInterruptedKeyProbeExactlyOnce() throws Exception {
    CountDownLatch inProbe = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger probes = new AtomicInteger();
    // NotPerformed clears the interrupted mark, so a waiter that enters the critical section
    // after the first probe finds nothing to do. A second probe can therefore only happen when
    // two threads are inside the per-key section at once, which is what the striped lock forbids.
    ReconcilingEffectJournal journal =
        ReconcilingEffectJournal.wrap(delegate)
            .reconciler(
                (key, ctx) -> {
                  probes.incrementAndGet();
                  inProbe.countDown();
                  try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return ReconcileOutcome.notPerformed();
                })
            .auditSink(audit)
            .clock(clock)
            .unknownPolicy(UnknownEffectPolicy.RETRY)
            .build();
    journal.begin("k");

    int threads = 6;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(pool.submit(() -> journal.lookup("k")));
      }
      assertThat(inProbe.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      Thread.sleep(100); // let the others queue on the key lock
      release.countDown();
      for (Future<?> f : futures) {
        f.get(10, java.util.concurrent.TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(probes).hasValue(1);
    assertThat(journal.tracker().isInterrupted("k")).isFalse();
  }

  @Test
  void resolveIsAuditedWithTheOperator() {
    ReconcilingEffectJournal journal =
        journal(ReconcileOutcome.unknown("?"), UnknownEffectPolicy.HOLD);
    journal.begin("k1");
    assertThatThrownBy(() -> journal.lookup("k1")).isInstanceOf(EffectUnresolvedException.class);

    journal.resolve("k1", "{\"status\":\"ok\"}", RunContext.builder().principal("ops@x").build());

    assertThat(journal.lookup("k1")).contains("{\"status\":\"ok\"}");
    AuditEvent resolved =
        audit.events().stream()
            .filter(e -> e.actionType().equals(ReconcilingEffectJournal.ACTION_RESOLVED))
            .findFirst()
            .orElseThrow();
    assertThat(resolved.actorId()).isEqualTo("ops@x");
    assertThat(resolved.attributes()).containsEntry("resolvedWith", "result");
    assertThat(resolved.outcome()).isEqualTo(AuditOutcome.SUCCESS);
  }

  @Test
  void aThrowingReconcilerIsReportedWithABoundedReason() {
    ReconcilingEffectJournal journal =
        ReconcilingEffectJournal.wrap(delegate)
            .reconciler(
                (key, ctx) -> {
                  throw new IllegalStateException("x".repeat(10_000));
                })
            .auditSink(audit)
            .clock(clock)
            .unknownPolicy(UnknownEffectPolicy.HOLD)
            .build();
    journal.begin("k");

    assertThatThrownBy(() -> journal.lookup("k"))
        .isInstanceOf(EffectUnresolvedException.class)
        .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(1000));
    assertThat(audit.events().get(0).detail().length()).isLessThan(1000);
  }
}
