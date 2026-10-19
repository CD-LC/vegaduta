package io.vegaduta.agent.core.cost;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.tool.ToolStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class CostLedgerTest {

  private static final CostModel MODEL =
      CostModel.builder()
          .toolCost("search", "0.010")
          .toolCost("email", "0.020")
          .defaultToolCost("0.001")
          .modelPrice("m1", ModelPrice.perMillion("1.00", "2.00"))
          .humanReviewCost("1.50")
          .build();

  @Test
  void aggregatesToolModelAndReviewSpend() {
    CostLedger ledger = new CostLedger(MODEL);
    RunContext run1 = RunContext.ofRun("r1");
    RunContext run2 = RunContext.ofRun("r2");

    ledger.recordToolCall(run1, "search", ToolStatus.OK);
    ledger.recordToolCall(run1, "email", ToolStatus.OK);
    ledger.recordToolCall(run1, "email", ToolStatus.DENIED);
    ledger.recordToolCall(run2, "other", ToolStatus.ERROR);
    ledger.recordModelUsage(run1, "m1", 1_000_000, 500_000);
    ledger.recordReview(run2, "approval-1");

    assertThat(ledger.toolCalls()).hasSize(4);
    assertThat(ledger.toolUsd()).isEqualByComparingTo("0.031");
    assertThat(ledger.modelUsd()).isEqualByComparingTo("2.000000");
    assertThat(ledger.reviewUsd()).isEqualByComparingTo("1.50");
    assertThat(ledger.totalUsd()).isEqualByComparingTo("3.531");
    assertThat(ledger.usdForRun("r1")).isEqualByComparingTo("2.030");
    assertThat(ledger.usdForRun("r2")).isEqualByComparingTo("1.501");
    assertThat(ledger.usdForRun("none")).isEqualByComparingTo("0");
  }

  @Test
  void onlyCallsThatRanAreCharged() {
    CostLedger ledger = new CostLedger(MODEL);
    assertThat(ledger.chargeableToolCost("search", ToolStatus.OK)).isEqualByComparingTo("0.010");
    assertThat(ledger.chargeableToolCost("search", ToolStatus.ERROR)).isEqualByComparingTo("0.010");
    for (ToolStatus notRun :
        List.of(
            ToolStatus.DENIED,
            ToolStatus.REPLAYED,
            ToolStatus.PENDING_APPROVAL,
            ToolStatus.BUDGET_EXCEEDED)) {
      assertThat(ledger.chargeableToolCost("search", notRun)).isEqualByComparingTo("0");
    }
  }

  @Test
  void isBoundedAndThreadSafe() throws InterruptedException {
    CostLedger ledger = new CostLedger(MODEL, 50);
    int threads = 8;
    int perThread = 100;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    List<Thread> workers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      Thread worker =
          new Thread(
              () -> {
                try {
                  start.await();
                  for (int i = 0; i < perThread; i++) {
                    ledger.recordToolCall(RunContext.ofRun("r"), "search", ToolStatus.OK);
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              });
      workers.add(worker);
      worker.start();
    }
    start.countDown();
    done.await();
    for (Thread w : workers) {
      w.join();
    }

    assertThat(ledger.toolCalls()).hasSize(50);
    assertThat(ledger.toolUsd()).isEqualByComparingTo("0.500");
    ledger.clear();
    assertThat(ledger.toolCalls()).isEmpty();
  }
}
