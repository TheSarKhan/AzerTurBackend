package az.azertur.booking;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BookingWorkflowTest {
  @Test
  void operatorMustReviewBeforePayment() {
    assertThrows(
        RuntimeException.class,
        () -> BookingWorkflow.payable("PENDING_REVIEW", 530, 159, 0, "DEPOSIT"));
    assertEquals("AWAITING_PAYMENT", BookingWorkflow.review("PENDING_REVIEW", true));
    assertEquals(159, BookingWorkflow.payable("AWAITING_PAYMENT", 530, 159, 0, "DEPOSIT"));
  }

  @Test
  void refusesRepeatedDeposits() {
    assertThrows(
        RuntimeException.class,
        () -> BookingWorkflow.payable("CONFIRMED", 530, 159, 159, "DEPOSIT"));
    assertEquals(371, BookingWorkflow.payable("CONFIRMED", 530, 159, 159, "BALANCE"));
    assertThrows(
        RuntimeException.class,
        () -> BookingWorkflow.payable("CONFIRMED", 530, 159, 530, "BALANCE"));
  }

  @Test
  void cannotReviewOrPayCancelledBookings() {
    assertThrows(RuntimeException.class, () -> BookingWorkflow.review("CANCELLED", true));
    assertThrows(
        RuntimeException.class,
        () -> BookingWorkflow.payable("CANCELLED", 530, 159, 159, "BALANCE"));
  }
}
