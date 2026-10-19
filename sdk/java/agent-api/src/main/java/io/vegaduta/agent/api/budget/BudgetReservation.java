package io.vegaduta.agent.api.budget;

/**
 * A slice of budget held for one call. Exactly one of {@link #commit(BudgetCharge)} or {@link
 * #release()} must be called; {@link #close()} releases if neither was.
 */
public interface BudgetReservation extends AutoCloseable {

  /** The amount held. */
  BudgetCharge reserved();

  /** Settles the reservation with the actual cost (which may differ from the estimate). */
  void commit(BudgetCharge actual);

  /** Gives the reservation back untouched (the call did not happen). */
  void release();

  @Override
  void close();
}
