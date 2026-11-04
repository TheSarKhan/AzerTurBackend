package az.azertur.document;
import az.azertur.customer.CustomerAuthService.Customer;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
@RestController
public class TravelDocumentController {
 private final TravelDocumentService service;
 public TravelDocumentController(TravelDocumentService service){this.service=service;}
 @ModelAttribute void privateResponse(jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store, private");}
 public record Review(String sha256,boolean customerChecked){}
 @GetMapping("/api/v1/operations/bookings/{id}/documents") Object list(@PathVariable UUID id){return service.list(id,null);}
 @PostMapping("/api/v1/operations/bookings/{id}/documents") Object upload(@PathVariable UUID id,@RequestParam MultipartFile file,@RequestParam String serviceKey,@RequestParam String kind,@RequestParam String title,@RequestParam OffsetDateTime startsAt,@RequestParam OffsetDateTime endsAt,@RequestParam String timezone,@RequestParam(defaultValue="")String instructions,@RequestParam(required=false)UUID replaces,Principal p)throws Exception{return service.upload(id,file,new TravelDocumentService.Upload(serviceKey,kind,title,startsAt,endsAt,timezone,instructions,replaces),p.getName());}
 @PostMapping("/api/v1/operations/bookings/{id}/documents/{document}/preview") Object preview(@PathVariable UUID id,@PathVariable UUID document,@RequestBody Review r,Principal p){return service.preview(id,document,r.sha256(),p.getName());}
 @PostMapping("/api/v1/operations/bookings/{id}/documents/{document}/publish") Object publish(@PathVariable UUID id,@PathVariable UUID document,@RequestBody Review r,Principal p){return service.publish(id,document,r.sha256(),r.customerChecked(),p.getName());}
 @PostMapping("/api/v1/operations/bookings/{id}/documents/{document}/revoke") Object revoke(@PathVariable UUID id,@PathVariable UUID document,Principal p){return service.revoke(id,document,p.getName());}
 @GetMapping("/api/v1/operations/bookings/{id}/documents/{document}/file") ResponseEntity<byte[]> operatorFile(@PathVariable UUID id,@PathVariable UUID document){return file(id,document,null);}
 @GetMapping("/api/v1/bookings/{id}/documents") Object customer(@PathVariable UUID id,@AuthenticationPrincipal Customer c){return service.list(id,c.id());}
 @GetMapping("/api/v1/bookings/{id}/documents/{document}") Object detail(@PathVariable UUID id,@PathVariable UUID document,@AuthenticationPrincipal Customer c){return service.get(id,document,c.id());}
 @GetMapping("/api/v1/bookings/{id}/documents/{document}/file") ResponseEntity<byte[]> customerFile(@PathVariable UUID id,@PathVariable UUID document,@AuthenticationPrincipal Customer c){return file(id,document,c.id());}
 private ResponseEntity<byte[]> file(UUID id,UUID document,UUID owner){var d=service.file(id,document,owner);return ResponseEntity.ok().contentType(MediaType.parseMediaType((String)d.get("mime"))).header("Cache-Control","no-store, private").header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","sandbox; default-src 'none'").header("Content-Disposition","inline; filename=\""+d.get("filename")+"\"").body((byte[])d.get("content"));}
}
