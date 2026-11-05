package az.azertur.staff;

import az.azertur.customer.AuthRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff/auth")
public class StaffAuthController {
  public record Login(@NotBlank @Email @Size(max = 254) String email, @NotBlank String password) {}

  private final StaffAuthService auth;
  private final AuthRateLimiter limiter;

  public StaffAuthController(StaffAuthService auth, AuthRateLimiter limiter) {
    this.auth = auth;
    this.limiter = limiter;
  }

  @PostMapping("/login")
  public ResponseEntity<?> login(@Valid @RequestBody Login input, HttpServletRequest req) {
    limiter.check(req.getRemoteAddr(), "staff:" + input.email());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(auth.login(input.email(), input.password()));
  }

  @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "operationsAuth")
  @GetMapping("/me")
  public ResponseEntity<?> me(@AuthenticationPrincipal StaffAuthService.Staff staff) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(staff);
  }

  @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "operationsAuth")
  @PostMapping("/logout")
  public ResponseEntity<?> logout(@RequestHeader("Authorization") String token) {
    auth.logout(token.substring(7));
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }
}
