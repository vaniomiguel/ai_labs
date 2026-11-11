package pt.conta.shared;

import java.math.*;

/** Parses and calculates money without binary floating point or silent cent rounding. */
public final class Money {
  public static final BigDecimal ZERO = new BigDecimal("0.00");

  private Money() {}

  /** Reads an optional decimal; callers distinguish missing values from zero. */
  public static BigDecimal optional(Object value) {
    if (value == null || value.toString().isBlank()) return null;
    try {
      var amount = new BigDecimal(value.toString()).setScale(2, RoundingMode.UNNECESSARY);
      if (amount.precision() > 19) throw new NumberFormatException("Montante fora do limite");
      return amount;
    } catch (ArithmeticException | NumberFormatException e) {
      throw DomainException.invalid("Valor monetário inválido: " + value);
    }
  }

  /** Reads a required, strictly positive euro amount. */
  public static BigDecimal positive(Object value) {
    BigDecimal amount = optional(value);
    if (amount == null || amount.signum() <= 0)
      throw DomainException.invalid("O montante tem de ser superior a zero");
    return amount;
  }

  /** Formats one monetary value for the API, preserving an unknown value as null. */
  public static String text(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }

  /** Treats null as zero only for optional components of a validated arithmetic check. */
  public static BigDecimal orZero(BigDecimal value) {
    return value == null ? ZERO : value;
  }
}
