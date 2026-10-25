package az.azertur.operations;

import az.azertur.booking.BookingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "operationsAuth")
@RestController
@RequestMapping("/api/v1/operations")
public class BookingOperationsController {
  private final BookingService bookings;

  public BookingOperationsController(BookingService bookings) {
    this.bookings = bookings;
  }

  public record Decision(
      boolean approve, @NotBlank @Size(min = 3, max = 2000) String note, UUID replacementQuoteId) {}

  @GetMapping("/bookings")
  public Object list() {
    return bookings.operations();
  }

  @GetMapping("/requests")
  public Object requests() {
    return bookings.operationsRequests();
  }

  @PostMapping("/bookings/{id}/review")
  public Object review(@PathVariable UUID id, @Valid @RequestBody Decision body, Principal user) {
    return bookings.review(id, body.approve(), body.note(), user.getName());
  }

  @PostMapping("/requests/{id}/resolve")
  public void resolve(@PathVariable UUID id, @Valid @RequestBody Decision body, Principal user) {
    bookings.resolve(id, body.approve(), body.note(), user.getName(), body.replacementQuoteId());
  }

  @GetMapping("/bookings/{id}/audit")
  public Object audit(@PathVariable UUID id) {
    return bookings.audit(id);
  }
}
