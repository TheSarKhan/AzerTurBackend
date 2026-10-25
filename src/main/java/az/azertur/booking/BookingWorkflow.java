package az.azertur.booking;

import az.azertur.shared.DomainException;

public final class BookingWorkflow {
  private BookingWorkflow() {}

  public static String review(String status, boolean approve) {
    if (!status.equals("PENDING_REVIEW")) throw DomainException.conflict("ALREADY_REVIEWED");
    return approve ? "AWAITING_PAYMENT" : "REJECTED";
  }

  public static int payable(String status, int total, int deposit, int paid, String kind) {
    if (kind.equals("DEPOSIT") && status.equals("AWAITING_PAYMENT") && paid == 0) return deposit;
    if (kind.equals("BALANCE") && status.equals("CONFIRMED") && paid > 0 && paid < total)
      return total - paid;
    throw DomainException.conflict("PAYMENT_NOT_ALLOWED");
  }
}
