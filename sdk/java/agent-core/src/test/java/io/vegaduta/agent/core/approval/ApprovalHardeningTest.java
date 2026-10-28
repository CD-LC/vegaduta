package io.vegaduta.agent.core.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.approval.ApprovalActionExecutor;
import io.vegaduta.agent.api.approval.ApprovalAlreadyDecidedException;
import io.vegaduta.agent.api.approval.ApprovalDecisionAuthorizer;
import io.vegaduta.agent.api.approval.ApprovalNotAuthorizedException;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.approval.ApprovalStatus;
import io.vegaduta.agent.api.approval.ApprovalStore;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.core.Governance;
import io.vegaduta.agent.core.GovernancePolicy;
import io.vegaduta.agent.core.audit.InMemoryAuditSink;
import io.vegaduta.agent.core.hook.HookEngine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Four-eyes default and opt-out, redacted failure results, compare-and-set across gates. */
class ApprovalHardeningTest {

  private static final RunContext CTX = RunContext.builder().tenantId("t1").principal("agent").build();
  private static final String ACTION = "tool:pay";

  private final InMemoryAuditSink audit = new InMemoryAuditSink();

  private DefaultApprovalGate gate(
      ApprovalStore store, ApprovalDecisionAuthorizer authorizer, ApprovalActionExecutor executor) {
    ApprovalActionExecutorRegistry registry = new ApprovalActionExecutorRegistry();
    registry.register(executor);
    return new DefaultApprovalGate(
        store,
        authorizer,
        registry,
        audit,
        new HookEngine(),
        Duration.ofMinutes(10),
        Clock.systemUTC());
  }

  private static ApprovalActionExecutor executor(java.util.function.Function<ApprovalRequest, String> body) {
    return new ApprovalActionExecutor() {
      @Override
      public String actionType() {
        return ACTION;
      }

      @Override
      public String execute(ApprovalRequest request) {
        return body.apply(request);
      }
    };
  }

  // ---- four-eyes default, explicit opt-out ------------------------------------------------

  @Test
  void governanceDefaultsToFourEyes() {
    Governance governance = Governance.builder().build();
    ApprovalRequest request =
        governance.approvals().request(ACTION, "pay", "{}", CTX);
    assertThat(request.requestedBy()).isEqualTo("agent");
    assertThatThrownBy(() -> governance.approvals().approve(request.id(), "agent"))
        .isInstanceOf(ApprovalNotAuthorizedException.class);
    assertThat(governance.approvals().approve(request.id(), "bob").status())
        .isEqualTo(ApprovalStatus.APPROVED);
  }

  @Test
  void optOutIsExplicit() {
    Governance viaPolicy =
        Governance.builder()
            .policy(GovernancePolicy.builder().fourEyes(false).build())
            .build();
    ApprovalRequest a = viaPolicy.approvals().request(ACTION, "pay", "{}", CTX);
    assertThat(viaPolicy.approvals().approve(a.id(), "agent").status())
        .isEqualTo(ApprovalStatus.APPROVED);

    Governance viaAuthorizer =
        Governance.builder().approvalAuthorizer(ApprovalDecisionAuthorizer.allowAny()).build();
    ApprovalRequest b = viaAuthorizer.approvals().request(ACTION, "pay", "{}", CTX);
    assertThat(viaAuthorizer.approvals().reject(b.id(), "agent", "self").status())
        .isEqualTo(ApprovalStatus.REJECTED);
  }

  @Test
  void permitAllIsTheSameOptOut() {
    ApprovalDecisionAuthorizer permitAll = ApprovalDecisionAuthorizer.permitAll();
    ApprovalRequest r =
        ApprovalRequest.pending(ACTION, "s", "{}", CTX, Instant.now(), Instant.now().plusSeconds(60));
    permitAll.authorize(r, r.requestedBy());
  }

  // ---- redacted failure results -------------------------------------------------------------

  @Test
  void failedExecutionStoresTypeAndRedactedMessage() {
    InMemoryApprovalStore store = new InMemoryApprovalStore();
    DefaultApprovalGate gate =
        gate(
            store,
            ApprovalDecisionAuthorizer.allowAny(),
            executor(
                r -> {
                  throw new IllegalStateException(
                      "provider rejected: Authorization: Bearer sk-live.ABC123 and api_key=hunter2"
                          + " token=abcdef\nnext line "
                          + "x".repeat(300));
                }));
    ApprovalRequest request = gate.request(ACTION, "pay", "{}", CTX);

    ApprovalRequest failed = gate.approve(request.id(), "bob");

    assertThat(failed.status()).isEqualTo(ApprovalStatus.FAILED);
    String result = failed.resultJson();
    assertThat(result).startsWith("IllegalStateException: provider rejected");
    assertThat(result)
        .doesNotContain("sk-live")
        .doesNotContain("hunter2")
        .doesNotContain("abcdef")
        .doesNotContain("\n")
        .contains("[redacted]");
    // type + ": " + at most 200 characters of message
    assertThat(result.length()).isLessThanOrEqualTo("IllegalStateException: ".length() + 200);
    assertThat(store.find(request.id()).orElseThrow().resultJson()).isEqualTo(result);

    List<AuditEvent> failures =
        audit.events().stream().filter(e -> e.outcome() == AuditOutcome.FAILURE).toList();
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0).detail()).isEqualTo(result);
  }

  @Test
  void exceptionWithoutMessageStoresJustTheType() {
    DefaultApprovalGate gate =
        gate(
            new InMemoryApprovalStore(),
            ApprovalDecisionAuthorizer.allowAny(),
            executor(
                r -> {
                  throw new UnsupportedOperationException();
                }));
    ApprovalRequest request = gate.request(ACTION, "pay", "{}", CTX);
    assertThat(gate.approve(request.id(), "bob").resultJson())
        .isEqualTo("UnsupportedOperationException");
  }

  // ---- compare-and-set across gates sharing one store ---------------------------------------

  @Test
  void twoGatesOnOneStoreExecuteAnApprovedRequestOnce() throws Exception {
    InMemoryApprovalStore store = new InMemoryApprovalStore();
    AtomicInteger runs = new AtomicInteger();
    ApprovalActionExecutor exec =
        executor(
            r -> {
              runs.incrementAndGet();
              return "{}";
            });
    int gates = 8;
    List<DefaultApprovalGate> all = new ArrayList<>();
    for (int i = 0; i < gates; i++) {
      all.add(gate(store, ApprovalDecisionAuthorizer.allowAny(), exec));
    }
    ApprovalRequest request = all.get(0).request(ACTION, "pay", "{}", CTX);

    ExecutorService pool = Executors.newFixedThreadPool(gates);
    CountDownLatch start = new CountDownLatch(1);
    List<String> winners = Collections.synchronizedList(new ArrayList<>());
    List<Throwable> losers = Collections.synchronizedList(new ArrayList<>());
    for (int i = 0; i < gates; i++) {
      DefaultApprovalGate g = all.get(i);
      String who = "u" + i;
      pool.submit(
          () -> {
            start.await();
            try {
              g.approve(request.id(), who);
              winners.add(who);
            } catch (ApprovalAlreadyDecidedException e) {
              losers.add(e);
            }
            return null;
          });
    }
    start.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

    assertThat(winners).hasSize(1);
    assertThat(losers).hasSize(gates - 1);
    assertThat(runs.get()).isEqualTo(1);
    assertThat(store.find(request.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.EXECUTED);
  }

  /** The first {@code find} returns the request as it was before another gate decided it. */
  private static final class StaleReadStore implements ApprovalStore {
    final InMemoryApprovalStore inner = new InMemoryApprovalStore();
    @Nullable ApprovalRequest stale;

    @Override
    public void save(ApprovalRequest request) {
      inner.save(request);
    }

    @Override
    public Optional<ApprovalRequest> find(UUID id) {
      if (stale != null) {
        ApprovalRequest s = stale;
        stale = null;
        return Optional.of(s);
      }
      return inner.find(id);
    }

    @Override
    public List<ApprovalRequest> findPending(@Nullable String tenantId, int limit) {
      return inner.findPending(tenantId, limit);
    }

    @Override
    public List<ApprovalRequest> findByRun(String runId) {
      return inner.findByRun(runId);
    }

    @Override
    public boolean saveIfStatus(ApprovalRequest request, ApprovalStatus expected) {
      return inner.saveIfStatus(request, expected);
    }
  }

  @Test
  void expiryDoesNotOverwriteADecisionThatLandedAfterTheRead() {
    StaleReadStore store = new StaleReadStore();
    Instant now = Instant.parse("2026-10-12T10:00:00Z");
    // a clock past the request's expiry
    Clock late = Clock.fixed(now.plus(Duration.ofHours(1)), ZoneOffset.UTC);
    DefaultApprovalGate gate =
        new DefaultApprovalGate(
            store,
            ApprovalDecisionAuthorizer.allowAny(),
            new ApprovalActionExecutorRegistry(),
            audit,
            new HookEngine(),
            Duration.ofMinutes(10),
            late);
    ApprovalRequest pending =
        ApprovalRequest.pending(ACTION, "pay", "{}", CTX, now, now.plus(Duration.ofMinutes(10)));
    store.save(pending);
    store.stale = pending; // this gate reads PENDING and past expiry ...
    store.inner.save(pending.withDecision(ApprovalStatus.APPROVED, now, "other", null)); // ... but

    assertThat(gate.find(pending.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.APPROVED);
    assertThat(store.inner.find(pending.id()).orElseThrow().status())
        .isEqualTo(ApprovalStatus.APPROVED);
    assertThat(audit.events().stream().filter(e -> e.outcome() == AuditOutcome.EXPIRED)).isEmpty();
  }
}
