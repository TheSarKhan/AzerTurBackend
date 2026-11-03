package az.azertur.amendment;
import az.azertur.customer.CustomerAuthService.Customer;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
@RestController
public class AmendmentController {
  private final AmendmentService service;
  public AmendmentController(AmendmentService service){this.service=service;}
  @GetMapping("/api/v1/operations/requests/{id}/amendment") Object context(@PathVariable UUID id){return service.context(id);}
  @PostMapping("/api/v1/operations/requests/{id}/amendment/calculate") Object calculate(@PathVariable UUID id,@Valid @RequestBody AmendmentService.Draft body){return service.calculate(id,body);}
  @PostMapping("/api/v1/operations/requests/{id}/amendment/draft") Object save(@PathVariable UUID id,@Valid @RequestBody AmendmentService.Draft body,Principal p){return service.save(id,body,p.getName());}
  @PostMapping("/api/v1/operations/requests/{id}/amendment/pdf") Object upload(@PathVariable UUID id,@RequestParam long version,@RequestParam MultipartFile file,Principal p)throws Exception{return service.upload(id,version,file,p.getName());}
  @PostMapping("/api/v1/operations/requests/{id}/amendment/preview") Object preview(@PathVariable UUID id,@Valid @RequestBody AmendmentService.Version body,Principal p){return service.preview(id,body,p.getName());}
  @PostMapping("/api/v1/operations/requests/{id}/amendment/send") Object send(@PathVariable UUID id,@Valid @RequestBody AmendmentService.Version body,Principal p){return service.send(id,body,p.getName());}
  @GetMapping("/api/v1/operations/requests/{id}/amendment/draft/{version}/pdf") ResponseEntity<byte[]> draftPdf(@PathVariable UUID id,@PathVariable long version){return pdf(service.operatorPdf(id,version,null));}
  @GetMapping("/api/v1/operations/requests/{id}/amendment/versions/{proposal}/pdf") ResponseEntity<byte[]> versionPdf(@PathVariable UUID id,@PathVariable UUID proposal){return pdf(service.operatorPdf(id,null,proposal));}
  @GetMapping("/api/v1/bookings/{id}/amendments") Object list(@PathVariable UUID id,@AuthenticationPrincipal Customer c){return service.customer(id,c.id());}
  @PostMapping("/api/v1/bookings/{id}/amendments/accept") Object accept(@PathVariable UUID id,@AuthenticationPrincipal Customer c,@Valid @RequestBody AmendmentService.Accept a){return service.accept(id,c.id(),a);}
  @PostMapping("/api/v1/bookings/{id}/amendments/decline") Object decline(@PathVariable UUID id,@AuthenticationPrincipal Customer c,@Valid @RequestBody AmendmentService.Decline a){return service.decline(id,c.id(),a);}
  @GetMapping("/api/v1/bookings/{id}/amendments/{proposal}/pdf") ResponseEntity<byte[]> customerPdf(@PathVariable UUID id,@PathVariable UUID proposal,@AuthenticationPrincipal Customer c){return pdf(service.pdf(id,proposal,c.id(),false));}
  @GetMapping("/api/v1/bookings/{id}/amendments/{proposal}/base-pdf") ResponseEntity<byte[]> basePdf(@PathVariable UUID id,@PathVariable UUID proposal,@AuthenticationPrincipal Customer c){return pdf(service.pdf(id,proposal,c.id(),true));}
  private ResponseEntity<byte[]> pdf(byte[] content){if(content==null)throw az.azertur.shared.DomainException.conflict("REVIEWED_PDF_REQUIRED");return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("Content-Disposition","inline; filename=amendment-contract.pdf").body(content);}
}
