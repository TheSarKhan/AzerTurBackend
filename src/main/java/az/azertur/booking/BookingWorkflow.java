package az.azertur.booking;

import az.azertur.shared.DomainException;

public final class BookingWorkflow {
  private BookingWorkflow() {}

  public static String review(String status, boolean approve) {
    if (!status.equals("PENDING_REVIEW")) throw DomainException.conflict("ALREADY_REVIEWED");
    // A request is accepted by sending an offer and a contract, never by a bare approval.
    if (approve) throw DomainException.conflict("OFFER_WORKFLOW_REQUIRED");
    return "REJECTED";
  }

}
