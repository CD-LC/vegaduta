package io.vegaduta.agent.api.budget;

/**
 * A spend limit for one run. Reservation happens <em>before</em> the call and is atomic, so
 * parallel tool calls cannot each pass a check and jointly overspend.
 */
public interface Budget {

  /**
   * Holds {@code estimate} against the remaining budget.
   *
   * @throws BudgetExceededException when the remaining budget cannot cover it
   */
  BudgetReservation reserve(BudgetCharge estimate);

  /** Everything committed so far. */
  BudgetCharge spent();

  /** The cap this budget enforces. */
  BudgetCharge cap();

  /** True when nothing more can be reserved. */
  boolean exhausted();
}
