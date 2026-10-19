package io.vegaduta.agent.core.budget;

import io.vegaduta.agent.api.budget.Budget;
import io.vegaduta.agent.api.budget.BudgetCharge;
import io.vegaduta.agent.api.budget.BudgetExceededException;
import io.vegaduta.agent.api.budget.BudgetReservation;
import java.math.BigDecimal;

/**
 * A budget whose reserve, commit and release are one critical section, so concurrent tool calls
 * cannot each pass a check and jointly overspend. A cap dimension of zero means unlimited.
 */
public final class AtomicBudget implements Budget {

  private final BudgetCharge cap;
  private final Object lock = new Object();
  private BudgetCharge spent = BudgetCharge.NONE;
  private BudgetCharge reserved = BudgetCharge.NONE;

  public AtomicBudget(BudgetCharge cap) {
    this.cap = cap;
  }

  /** A budget that never refuses. */
  public static AtomicBudget unlimited() {
    return new AtomicBudget(BudgetCharge.NONE);
  }

  @Override
  public BudgetReservation reserve(BudgetCharge estimate) {
    synchronized (lock) {
      BudgetCharge committedPlusHeld = spent.plus(reserved);
      if (!fits(committedPlusHeld.plus(estimate))) {
        throw new BudgetExceededException(estimate, spent, cap);
      }
      reserved = reserved.plus(estimate);
      return new Reservation(estimate);
    }
  }

  private boolean fits(BudgetCharge total) {
    if (cap.toolCalls() > 0 && total.toolCalls() > cap.toolCalls()) {
      return false;
    }
    if (cap.tokens() > 0 && total.tokens() > cap.tokens()) {
      return false;
    }
    return cap.usd().signum() <= 0 || total.usd().compareTo(cap.usd()) <= 0;
  }

  @Override
  public BudgetCharge spent() {
    synchronized (lock) {
      return spent;
    }
  }

  @Override
  public BudgetCharge cap() {
    return cap;
  }

  @Override
  public boolean exhausted() {
    synchronized (lock) {
      BudgetCharge total = spent.plus(reserved);
      boolean callsOut = cap.toolCalls() > 0 && total.toolCalls() >= cap.toolCalls();
      boolean tokensOut = cap.tokens() > 0 && total.tokens() >= cap.tokens();
      boolean usdOut = cap.usd().signum() > 0 && total.usd().compareTo(cap.usd()) >= 0;
      return callsOut || tokensOut || usdOut;
    }
  }

  private final class Reservation implements BudgetReservation {
    private final BudgetCharge held;
    private boolean settled;

    Reservation(BudgetCharge held) {
      this.held = held;
    }

    @Override
    public BudgetCharge reserved() {
      return held;
    }

    @Override
    public void commit(BudgetCharge actual) {
      synchronized (lock) {
        if (settled) {
          return;
        }
        settled = true;
        reserved = subtract(reserved, held);
        spent = spent.plus(actual);
      }
    }

    @Override
    public void release() {
      synchronized (lock) {
        if (settled) {
          return;
        }
        settled = true;
        reserved = subtract(reserved, held);
      }
    }

    @Override
    public void close() {
      release();
    }
  }

  private static BudgetCharge subtract(BudgetCharge a, BudgetCharge b) {
    long calls = Math.max(0, a.toolCalls() - b.toolCalls());
    long tokens = Math.max(0, a.tokens() - b.tokens());
    BigDecimal usd = a.usd().subtract(b.usd());
    return new BudgetCharge(calls, tokens, usd.signum() < 0 ? BigDecimal.ZERO : usd);
  }
}
