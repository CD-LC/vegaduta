package io.vegaduta.agent.api.budget;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What a call is expected to cost, or did cost.
 *
 * @param toolCalls number of tool calls (usually 1)
 * @param tokens model tokens consumed (0 when unknown)
 * @param usd money, in US dollars (zero when unknown)
 */
public record BudgetCharge(long toolCalls, long tokens, BigDecimal usd) {

  public static final BudgetCharge ONE_TOOL_CALL = new BudgetCharge(1, 0, BigDecimal.ZERO);
  public static final BudgetCharge NONE = new BudgetCharge(0, 0, BigDecimal.ZERO);

  public BudgetCharge {
    Objects.requireNonNull(usd, "usd");
    if (toolCalls < 0 || tokens < 0 || usd.signum() < 0) {
      throw new IllegalArgumentException("charges must not be negative");
    }
  }

  public static BudgetCharge tokens(long tokens) {
    return new BudgetCharge(0, tokens, BigDecimal.ZERO);
  }

  public static BudgetCharge usd(BigDecimal usd) {
    return new BudgetCharge(0, 0, usd);
  }

  public BudgetCharge plus(BudgetCharge other) {
    return new BudgetCharge(toolCalls + other.toolCalls, tokens + other.tokens, usd.add(other.usd));
  }
}
