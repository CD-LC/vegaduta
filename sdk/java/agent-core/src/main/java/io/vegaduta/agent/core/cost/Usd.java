package io.vegaduta.agent.core.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money arithmetic conventions shared by the cost package: six decimal places, half-up. */
final class Usd {

  static final int SCALE = 6;
  static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
  static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);

  private Usd() {}

  static BigDecimal scale(BigDecimal value) {
    return value.setScale(SCALE, RoundingMode.HALF_UP);
  }

  static BigDecimal nonNegative(BigDecimal value, String name) {
    if (value.signum() < 0) {
      throw new IllegalArgumentException(name + " must not be negative: " + value);
    }
    return scale(value);
  }

  static BigDecimal divide(BigDecimal numerator, long denominator) {
    if (denominator <= 0) {
      return ZERO;
    }
    return numerator.divide(BigDecimal.valueOf(denominator), SCALE, RoundingMode.HALF_UP);
  }
}
