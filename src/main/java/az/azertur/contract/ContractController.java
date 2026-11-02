package az.azertur.contract;

import az.azertur.customer.CustomerAuthService.Customer;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ContractController {
  private final ContractService service;
  public ContractController(ContractService service) { this.service=service; }
  public record Confirmation(String sha256) {}
  @GetMapping("/api/v1/operations/bookings/{id}/contract")
  Object operator(@PathVariable UUID id) { return service.operator(id); }
  @GetMapping("/api/v1/operations/bookings/{id}/contract-offer")
  Object offerContext(@PathVariable UUID id) { return service.offerContext(id); }
  @PostMapping("/api/v1/operations/bookings/{id}/contract")
  Object upload(@PathVariable UUID id,@RequestParam("file") MultipartFile file,@RequestParam UUID offerId,Principal user) throws Exception { return service.upload(id,file,offerId,user.getName()); }
  @PostMapping("/api/v1/operations/bookings/{id}/contract/{doc}/send")
  Object send(@PathVariable UUID id,@PathVariable UUID doc,Principal user) { return service.send(id,doc,user.getName()); }
  @GetMapping("/api/v1/operations/bookings/{id}/contract/{doc}/pdf")
  ResponseEntity<byte[]> operatorPdf(@PathVariable UUID id,@PathVariable UUID doc) { return pdf(service.operatorPdf(id,doc)); }
  @GetMapping("/api/v1/bookings/{id}/contract")
  Object customer(@PathVariable UUID id,@AuthenticationPrincipal Customer user) { return service.customer(id,user.id()); }
  @GetMapping("/api/v1/bookings/{id}/contract/{doc}/pdf")
  ResponseEntity<byte[]> customerPdf(@PathVariable UUID id,@PathVariable UUID doc,@AuthenticationPrincipal Customer user) { return pdf(service.customerPdf(id,doc,user.id())); }
  @PostMapping("/api/v1/bookings/{id}/contract/{doc}/accept")
  Object accept(@PathVariable UUID id,@PathVariable UUID doc,@AuthenticationPrincipal Customer user,@RequestBody Confirmation body) { return service.accept(id,doc,user.id(),body.sha256()); }
  private ResponseEntity<byte[]> pdf(byte[] bytes) {
    return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("Content-Disposition","inline; filename=contract.pdf").contentType(MediaType.APPLICATION_PDF).body(bytes);
  }
}
