package az.azertur.booking;

import az.azertur.customer.CustomerAuthService.Customer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "customerSession")
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {
  private final BookingService bookings;

  public BookingController(BookingService bookings) {
    this.bookings = bookings;
  }

  public record Create(@NotNull UUID quoteId, @NotNull @Size(max = 2000) String note) {}

  public record Payment(
      @NotNull @Pattern(regexp = "DEPOSIT|BALANCE") String kind, boolean success) {}

  public record Request(
      @NotNull @Pattern(regexp = "CHANGE|CANCEL") String kind,
      @NotBlank @Size(min = 5, max = 2000) String message) {}

  @GetMapping
  public Object list(@AuthenticationPrincipal Customer customer) {
    return bookings.list(customer.id());
  }

  @GetMapping("/requests")
  public Object requests(@AuthenticationPrincipal Customer customer) {
    return bookings.requests(customer.id());
  }

  @PostMapping
  public Object create(
      @AuthenticationPrincipal Customer customer, @Valid @RequestBody Create body) {
    return bookings.create(customer, body.quoteId(), body.note());
  }

  @PostMapping("/{id}/demo-payment")
  public Object pay(
      @PathVariable UUID id,
      @AuthenticationPrincipal Customer customer,
      @RequestHeader("Idempotency-Key") @Size(min = 16, max = 80) String key,
      @Valid @RequestBody Payment body) {
    if (key.length() < 16 || key.length() > 80)
      throw az.azertur.shared.DomainException.bad("INVALID_IDEMPOTENCY_KEY");
    return bookings.pay(id, customer.id(), key, body.kind(), body.success());
  }

  @PostMapping("/{id}/requests")
  public Object request(
      @PathVariable UUID id,
      @AuthenticationPrincipal Customer customer,
      @Valid @RequestBody Request body) {
    return bookings.request(id, customer.id(), body.kind(), body.message());
  }
}
