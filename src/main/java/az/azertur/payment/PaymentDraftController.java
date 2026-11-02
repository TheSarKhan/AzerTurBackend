package az.azertur.payment;
import az.azertur.customer.CustomerAuthService.Customer;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
public class PaymentDraftController {
 private final PaymentDraftService service;
 public PaymentDraftController(PaymentDraftService service){this.service=service;}
 @GetMapping("/api/v1/operations/bookings/{id}/payment-context") Object context(@PathVariable UUID id){return service.context(id);}
 @GetMapping("/api/v1/bookings/{id}/payment-context") Object customer(@PathVariable UUID id,@AuthenticationPrincipal Customer c){return service.customerContext(id,c.id());}
 @GetMapping("/api/v1/operations/bookings/{id}/payment-draft") Object get(@PathVariable UUID id){return service.draft(id);}
 @PostMapping("/api/v1/operations/bookings/{id}/payment-draft") Object save(@PathVariable UUID id,@Valid @RequestBody PaymentDraftService.Draft body,Principal p){return service.save(id,body,p.getName());}
 @PostMapping("/api/v1/operations/bookings/{id}/payment-draft/preview") Object preview(@PathVariable UUID id,@Valid @RequestBody PaymentDraftService.Version body){return service.preview(id,body.version());}
 @PostMapping("/api/v1/operations/bookings/{id}/payment-draft/send") Object send(@PathVariable UUID id,@Valid @RequestBody PaymentDraftService.Version body,Principal p){return service.send(id,body.version(),p.getName());}
}
