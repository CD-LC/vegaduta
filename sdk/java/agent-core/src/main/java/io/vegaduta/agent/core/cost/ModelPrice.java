package io.vegaduta.agent.core.cost;

import io.vegaduta.agent.api.Experimental;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * List price of one model, in US dollars per million tokens.
 *
 * @param inputPerMillion price of one million prompt (input) tokens
 * @param outputPerMillion price of one million completion (output) tokens
 */
@Experimental
public record ModelPrice(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {

  public ModelPrice {
    Objects.requireNonNull(inputPerMillion, "inputPerMillion");
    Objects.requireNonNull(outputPerMillion, "outputPerMillion");
    inputPerMillion = Usd.nonNegative(inputPerMillion, "inputPerMillion");
    outputPerMillion = Usd.nonNegative(outputPerMillion, "outputPerMillion");
  }

  /** Convenience for literal prices such as {@code ModelPrice.perMillion("3.00", "15.00")}. */
  public static ModelPrice perMillion(String inputPerMillion, String outputPerMillion) {
    return new ModelPrice(new BigDecimal(inputPerMillion), new BigDecimal(outputPerMillion));
  }

  /** A price of zero for both directions; for local or flat-rate models. */
  public static ModelPrice free() {
    return new ModelPrice(BigDecimal.ZERO, BigDecimal.ZERO);
  }

  /** The cost of one call, rounded half-up to six decimal places. */
  public BigDecimal costOf(long inputTokens, long outputTokens) {
    if (inputTokens < 0 || outputTokens < 0) {
      throw new IllegalArgumentException("token counts must not be negative");
    }
    BigDecimal in = inputPerMillion.multiply(BigDecimal.valueOf(inputTokens));
    BigDecimal out = outputPerMillion.multiply(BigDecimal.valueOf(outputTokens));
    return in.add(out).divide(Usd.MILLION, Usd.SCALE, RoundingMode.HALF_UP);
  }
}
