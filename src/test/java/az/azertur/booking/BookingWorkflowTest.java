package az.azertur.booking;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BookingWorkflowTest {
  @Test
  void aRequestCanOnlyBeRejectedHere() {
    assertEquals("REJECTED", BookingWorkflow.review("PENDING_REVIEW", false));
  }

  @Test
  void approvalGoesThroughTheOfferWorkflow() {
    var error =
        assertThrows(RuntimeException.class, () -> BookingWorkflow.review("PENDING_REVIEW", true));
    assertTrue(error.getMessage().contains("OFFER_WORKFLOW_REQUIRED"));
  }

  @Test
  void cannotReviewTwiceOrCancelledBookings() {
    assertThrows(RuntimeException.class, () -> BookingWorkflow.review("REJECTED", false));
    assertThrows(RuntimeException.class, () -> BookingWorkflow.review("CANCELLED", true));
    assertThrows(RuntimeException.class, () -> BookingWorkflow.review("CONFIRMED", false));
  }
}
