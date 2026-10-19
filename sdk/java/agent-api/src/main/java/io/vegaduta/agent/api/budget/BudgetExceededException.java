package io.vegaduta.agent.api.budget;

import io.vegaduta.agent.api.GovernanceException;

public class BudgetExceededException extends GovernanceException {

  private static final long serialVersionUID = 1L;

  private final BudgetCharge requested;
  private final BudgetCharge spent;
  private final BudgetCharge cap;

  public BudgetExceededException(BudgetCharge requested, BudgetCharge spent, BudgetCharge cap) {
    super("Budget exceeded: requested " + requested + ", already spent " + spent + ", cap " + cap);
    this.requested = requested;
    this.spent = spent;
    this.cap = cap;
  }

  public BudgetCharge requested() {
    return requested;
  }

  public BudgetCharge spent() {
    return spent;
  }

  public BudgetCharge cap() {
    return cap;
  }
}
