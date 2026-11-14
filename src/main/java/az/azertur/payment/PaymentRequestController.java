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
  @PostMapping("/api/v1/operations/bookings/{id}/payment-requests/{requestId}/mark-paid")
  public Object markPaid(@PathVariable UUID id,@PathVariable UUID requestId,@Valid @RequestBody PaymentRequestService.MarkPaid input,org.springframework.security.core.Authentication user) {
    boolean finance=user.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_FINANCE"));
    return service.markPaid(id,requestId,input,user.getName(),finance);
  }
  @PostMapping("/api/v1/operations/bookings/{id}/payment-requests/{requestId}/close")
  public Object close(@PathVariable UUID id,@PathVariable UUID requestId,@Valid @RequestBody PaymentRequestService.Close input,Principal user) { return service.close(id,requestId,input,user.getName()); }
  @GetMapping("/api/v1/bookings/{id}/payment-requests")
  public Object customer(@PathVariable UUID id,@AuthenticationPrincipal Customer user) { return service.customer(id,user.id()); }
}
