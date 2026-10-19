package io.vegaduta.agent.core.cost;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.tool.ToolStatus;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Thread-safe, in-memory record of everything a set of runs cost: tool calls, model usage and human
 * review. Entries are appended by {@link CostObserver} and {@link CostAuditSink} (or by the host
 * directly for model usage, which the SDK cannot see) and read back by {@link ShowbackReport}.
 *
 * <p>The ledger is bounded: once {@code capacity} entries of one kind are held, the oldest is
 * dropped, so a long-lived process does not grow without limit. Export a report (or drain the
 * ledger into a durable store) before that happens if every entry matters.
 */
@Experimental
public final class CostLedger {

  /** What one governed tool call cost. */
  public record ToolCallCost(
      RunContext context, String toolName, BigDecimal usd, ToolStatus status) {
    public ToolCallCost {
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(toolName, "toolName");
      Objects.requireNonNull(status, "status");
      usd = Usd.nonNegative(Objects.requireNonNull(usd, "usd"), "usd");
    }
  }

  /** What one model call cost. */
  public record ModelUsage(
      RunContext context, String model, long inputTokens, long outputTokens, BigDecimal usd) {
    public ModelUsage {
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(model, "model");
      if (inputTokens < 0 || outputTokens < 0) {
        throw new IllegalArgumentException("token counts must not be negative");
      }
      usd = Usd.nonNegative(Objects.requireNonNull(usd, "usd"), "usd");
    }

    public long totalTokens() {
      return inputTokens + outputTokens;
    }
  }

  /** What one human approval decision cost. */
  public record ReviewCost(RunContext context, String approvalId, BigDecimal usd) {
    public ReviewCost {
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(approvalId, "approvalId");
      usd = Usd.nonNegative(Objects.requireNonNull(usd, "usd"), "usd");
    }
  }

  /** A consistent view of all three ledgers plus their totals, taken under one lock. */
  public record Snapshot(
      List<ToolCallCost> toolCalls,
      List<ModelUsage> modelUsages,
      List<ReviewCost> reviews,
      BigDecimal toolUsd,
      BigDecimal modelUsd,
      BigDecimal reviewUsd) {
    public BigDecimal totalUsd() {
      return toolUsd.add(modelUsd).add(reviewUsd);
    }
  }

  private final CostModel model;
  private final int capacity;
  private final Object lock = new Object();
  // Deques: eviction at capacity is O(1) instead of an array shift under the lock.
  private final Deque<ToolCallCost> toolCalls = new ArrayDeque<>();
  private final Deque<ModelUsage> modelUsages = new ArrayDeque<>();
  private final Deque<ReviewCost> reviews = new ArrayDeque<>();
  // Running totals over the retained entries, so reads do not copy the lists.
  private BigDecimal toolTotal = Usd.ZERO;
  private BigDecimal modelTotal = Usd.ZERO;
  private BigDecimal reviewTotal = Usd.ZERO;

  public CostLedger(CostModel model) {
    this(model, 100_000);
  }

  public CostLedger(CostModel model, int capacity) {
    this.model = Objects.requireNonNull(model, "model");
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    this.capacity = capacity;
  }

  public CostModel model() {
    return model;
  }

  // --- recording -------------------------------------------------------------------------------

  public void record(ToolCallCost entry) {
    synchronized (lock) {
      ToolCallCost evicted = append(toolCalls, entry);
      toolTotal = toolTotal.add(entry.usd()).subtract(evicted == null ? Usd.ZERO : evicted.usd());
    }
  }

  /** Records a tool call priced from the cost model. */
  public ToolCallCost recordToolCall(RunContext context, String toolName, ToolStatus status) {
    ToolCallCost entry =
        new ToolCallCost(context, toolName, chargeableToolCost(toolName, status), status);
    record(entry);
    return entry;
  }

  public void record(ModelUsage entry) {
    synchronized (lock) {
      ModelUsage evicted = append(modelUsages, entry);
      modelTotal = modelTotal.add(entry.usd()).subtract(evicted == null ? Usd.ZERO : evicted.usd());
    }
  }

  /** Records a model call priced from the cost model. */
  public ModelUsage recordModelUsage(
      RunContext context, String modelId, long inputTokens, long outputTokens) {
    ModelUsage entry =
        new ModelUsage(
            context,
            modelId,
            inputTokens,
            outputTokens,
            model.modelCost(modelId, inputTokens, outputTokens));
    record(entry);
    return entry;
  }

  public void record(ReviewCost entry) {
    synchronized (lock) {
      ReviewCost evicted = append(reviews, entry);
      reviewTotal =
          reviewTotal.add(entry.usd()).subtract(evicted == null ? Usd.ZERO : evicted.usd());
    }
  }

  /** Records a human approval decision priced from the cost model. */
  public ReviewCost recordReview(RunContext context, String approvalId) {
    ReviewCost entry = new ReviewCost(context, approvalId, model.humanReviewCost());
    record(entry);
    return entry;
  }

  /**
   * The USD a tool call of the given outcome is charged. A tool that ran ({@link ToolStatus#OK},
   * {@link ToolStatus#ERROR}) costs its listed price; one that was refused, replayed from the
   * journal or parked for approval did not run and costs nothing.
   */
  public BigDecimal chargeableToolCost(String toolName, ToolStatus status) {
    return switch (status) {
      case OK, ERROR -> model.toolCost(toolName);
      case REPLAYED, DENIED, PENDING_APPROVAL, BUDGET_EXCEEDED -> Usd.ZERO;
    };
  }

  /** Appends, evicting and returning the oldest entry when at capacity. */
  private <T> @org.jspecify.annotations.Nullable T append(Deque<T> deque, T entry) {
    T evicted = deque.size() >= capacity ? deque.pollFirst() : null;
    deque.addLast(entry);
    return evicted;
  }

  // --- reading ---------------------------------------------------------------------------------

  /** Every list and total as of one instant. */
  public Snapshot snapshot() {
    synchronized (lock) {
      return new Snapshot(
          List.copyOf(toolCalls),
          List.copyOf(modelUsages),
          List.copyOf(reviews),
          toolTotal,
          modelTotal,
          reviewTotal);
    }
  }

  public List<ToolCallCost> toolCalls() {
    synchronized (lock) {
      return List.copyOf(toolCalls);
    }
  }

  public List<ModelUsage> modelUsages() {
    synchronized (lock) {
      return List.copyOf(modelUsages);
    }
  }

  public List<ReviewCost> reviews() {
    synchronized (lock) {
      return List.copyOf(reviews);
    }
  }

  /** USD spent on tool calls across every run. */
  public BigDecimal toolUsd() {
    synchronized (lock) {
      return toolTotal;
    }
  }

  /** USD spent on model calls across every run. */
  public BigDecimal modelUsd() {
    synchronized (lock) {
      return modelTotal;
    }
  }

  /** USD spent on human review across every run. */
  public BigDecimal reviewUsd() {
    synchronized (lock) {
      return reviewTotal;
    }
  }

  /** USD spent in total across every run, as of one instant. */
  public BigDecimal totalUsd() {
    synchronized (lock) {
      return toolTotal.add(modelTotal).add(reviewTotal);
    }
  }

  /** Total USD attributed to one run. */
  public BigDecimal usdForRun(String runId) {
    BigDecimal total = Usd.ZERO;
    synchronized (lock) {
      for (ToolCallCost t : toolCalls) {
        if (t.context().runId().equals(runId)) {
          total = total.add(t.usd());
        }
      }
      for (ModelUsage m : modelUsages) {
        if (m.context().runId().equals(runId)) {
          total = total.add(m.usd());
        }
      }
      for (ReviewCost r : reviews) {
        if (r.context().runId().equals(runId)) {
          total = total.add(r.usd());
        }
      }
    }
    return total;
  }

  /** Removes every entry. */
  public void clear() {
    synchronized (lock) {
      toolCalls.clear();
      modelUsages.clear();
      reviews.clear();
      toolTotal = Usd.ZERO;
      modelTotal = Usd.ZERO;
      reviewTotal = Usd.ZERO;
    }
  }

  static BigDecimal sum(List<BigDecimal> values) {
    BigDecimal total = Usd.ZERO;
    for (BigDecimal v : values) {
      total = total.add(v);
    }
    return total;
  }
}
