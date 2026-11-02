package az.azertur.payment;

import az.azertur.customer.CustomerAuthService.Customer;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentRequestController {
  private final PaymentRequestService service;
  public PaymentRequestController(PaymentRequestService service) { this.service=service; }
  @GetMapping("/api/v1/operations/bookings/{id}/payment-requests")
  public Object list(@PathVariable UUID id) { return service.list(id); }
  @PostMapping("/api/v1/operations/bookings/{id}/payment-requests")
  public Object issue(@PathVariable UUID id,@Valid @RequestBody PaymentRequestService.Issue input,Principal user) { return service.issue(id,input,user.getName()); }
  @GetMapping("/api/v1/bookings/{id}/payment-requests")
  public Object customer(@PathVariable UUID id,@AuthenticationPrincipal Customer user) { return service.customer(id,user.id()); }
}
