package az.azertur.payment;

import az.azertur.shared.DomainException;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class PaymentAmounts {
  private PaymentAmounts() {}
  public static long minor(BigDecimal amount) {
    try {
      if (amount == null || amount.signum() <= 0) throw DomainException.bad("INVALID_AMOUNT");
      return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    } catch (ArithmeticException ex) { throw DomainException.bad("INVALID_AMOUNT"); }
  }
  public static void within(long amount,long allocated,long total) {
    if (amount <= 0 || allocated < 0 || total <= 0 || allocated > total || amount > total-allocated)
      throw DomainException.conflict("PAYMENT_EXCEEDS_CONTRACT");
  }
}
