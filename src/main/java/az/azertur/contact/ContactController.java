package az.azertur.contact;

import az.azertur.event.PublicRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ContactController {
  public record Message(
      @NotBlank @Size(min = 2, max = 80) String name,
      @NotBlank @Size(max = 254) @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") String email,
      @NotNull @Pattern(regexp = "general|custom|group|partner") String topic,
      @NotBlank @Size(min = 5, max = 2000) String message,
      @Pattern(regexp = "en|ru|az") String locale) {}

  private final ContactService contact;
  private final PublicRateLimiter limiter;

  public ContactController(ContactService contact, PublicRateLimiter limiter) {
    this.contact = contact;
    this.limiter = limiter;
  }

  /** Public: anyone can write to the team. Limited per visitor address. */
  @PostMapping("/api/v1/contact")
  public ResponseEntity<Void> submit(@Valid @RequestBody Message body, HttpServletRequest req) {
    limiter.check(req, "contact", 5);
    contact.submit(body.name(), body.email(), body.topic(), body.message(), body.locale() == null ? "en" : body.locale());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/api/v1/operations/contact-messages")
  public Object list() {
    return contact.list();
  }

  @PostMapping("/api/v1/operations/contact-messages/{id}/handled")
  public Object handled(@PathVariable UUID id, Principal user) {
    return contact.handled(id, user.getName());
  }
}
