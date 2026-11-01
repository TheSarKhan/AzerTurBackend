package az.azertur.offer;

import az.azertur.customer.CustomerAuthService.Customer;
import jakarta.validation.Valid;
import java.security.Principal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class OfferController {
  private final OfferService offers;

  public OfferController(OfferService offers) {
    this.offers = offers;
  }

  @SecurityRequirement(name="operationsAuth")
  @GetMapping("/api/v1/operations/bookings/{id}/customer")
  public Object customerInfo(@PathVariable UUID id) {
    return offers.customerInfo(id);
  }

  @SecurityRequirement(name="operationsAuth")
  @GetMapping("/api/v1/operations/bookings/{id}/offer")
  public Object get(@PathVariable UUID id) {
    return offers.get(id);
  }

  @SecurityRequirement(name="operationsAuth")
  @PostMapping("/api/v1/operations/bookings/{id}/offer")
  public Object save(
      @PathVariable UUID id, @Valid @RequestBody OfferService.Draft body, Principal user) {
    return offers.save(id, body, user.getName());
  }

  @SecurityRequirement(name="operationsAuth")
  @PostMapping("/api/v1/operations/bookings/{id}/offer/preview")
  public Object preview(@PathVariable UUID id, @Valid @RequestBody OfferService.Version body) {
    return offers.preview(id, body.version());
  }

  @SecurityRequirement(name="operationsAuth")
  @PostMapping("/api/v1/operations/bookings/{id}/offer/send")
  public Object send(
      @PathVariable UUID id, @Valid @RequestBody OfferService.Version body, Principal user) {
    return offers.send(id, body.version(), user.getName());
  }

  @SecurityRequirement(name="customerSession")
  @GetMapping("/api/v1/bookings/offers")
  public Object customer(@AuthenticationPrincipal Customer customer) {
    return offers.customerOffers(customer.id());
  }
}
