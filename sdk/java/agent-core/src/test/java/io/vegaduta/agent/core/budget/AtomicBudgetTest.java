package io.vegaduta.agent.core.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vegaduta.agent.api.budget.BudgetCharge;
import io.vegaduta.agent.api.budget.BudgetExceededException;
import io.vegaduta.agent.api.budget.BudgetReservation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AtomicBudgetTest {

  @Test
  void concurrentReservationsNeverExceedTheCap() throws Exception {
    AtomicBudget budget = new AtomicBudget(new BudgetCharge(10, 0, BigDecimal.ZERO));
    int threads = 40;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger granted = new AtomicInteger();
    AtomicInteger refused = new AtomicInteger();
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      futures.add(
          pool.submit(
              () -> {
                go.await();
                try (BudgetReservation r = budget.reserve(BudgetCharge.ONE_TOOL_CALL)) {
                  granted.incrementAndGet();
                  r.commit(BudgetCharge.ONE_TOOL_CALL);
                } catch (BudgetExceededException e) {
                  refused.incrementAndGet();
                }
                return null;
              }));
    }
    go.countDown();
    for (Future<?> f : futures) {
      f.get();
    }
    pool.shutdown();

    assertThat(granted).hasValue(10);
    assertThat(refused).hasValue(30);
    assertThat(budget.spent().toolCalls()).isEqualTo(10);
    assertThat(budget.exhausted()).isTrue();
  }

  @Test
  void releaseGivesTheReservationBack() {
    AtomicBudget budget = new AtomicBudget(new BudgetCharge(1, 0, BigDecimal.ZERO));
    BudgetReservation r = budget.reserve(BudgetCharge.ONE_TOOL_CALL);
    assertThat(budget.exhausted()).isTrue();
    r.release();
    assertThat(budget.exhausted()).isFalse();
    assertThat(budget.spent().toolCalls()).isZero();
    r.commit(BudgetCharge.ONE_TOOL_CALL);
    assertThat(budget.spent().toolCalls()).as("settled twice is a no-op").isZero();
  }

  @Test
  void usdAndTokenCapsApply() {
    AtomicBudget budget = new AtomicBudget(new BudgetCharge(0, 100, new BigDecimal("1.00")));
    budget.reserve(BudgetCharge.tokens(60)).commit(BudgetCharge.tokens(60));
    assertThatThrownBy(() -> budget.reserve(BudgetCharge.tokens(50)))
        .isInstanceOf(BudgetExceededException.class);
    budget
        .reserve(BudgetCharge.usd(new BigDecimal("0.75")))
        .commit(BudgetCharge.usd(new BigDecimal("0.75")));
    assertThatThrownBy(() -> budget.reserve(BudgetCharge.usd(new BigDecimal("0.30"))))
        .isInstanceOf(BudgetExceededException.class);
    assertThat(AtomicBudget.unlimited().reserve(new BudgetCharge(1_000, 1_000_000, BigDecimal.TEN)))
        .isNotNull();
  }

  @Test
  void providerKeepsOneBudgetPerRun() {
    InMemoryBudgetProvider provider =
        new InMemoryBudgetProvider(new BudgetCharge(1, 0, BigDecimal.ZERO), 2);
    var a = provider.budgetFor(io.vegaduta.agent.api.RunContext.ofRun("a"));
    assertThat(provider.budgetFor(io.vegaduta.agent.api.RunContext.ofRun("a"))).isSameAs(a);
    provider.budgetFor(io.vegaduta.agent.api.RunContext.ofRun("b"));
    provider.budgetFor(io.vegaduta.agent.api.RunContext.ofRun("c"));
    assertThat(provider.budgetFor(io.vegaduta.agent.api.RunContext.ofRun("a")))
        .as("evicted as least recently used")
        .isNotSameAs(a);
  }
}
