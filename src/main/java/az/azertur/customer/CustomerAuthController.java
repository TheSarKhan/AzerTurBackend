package az.azertur.customer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class CustomerAuthController {
  private final CustomerAuthService auth;
  private final AuthRateLimiter limiter;

  public CustomerAuthController(CustomerAuthService auth, AuthRateLimiter limiter) {
    this.auth = auth;
    this.limiter = limiter;
  }

  public record Register(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 12, max = 72) String password,
      @NotBlank @Size(min = 2, max = 100) String name,
      @NotNull @Pattern(regexp = "en|ru|az") String locale) {}

  public record Login(
      @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {}

  @PostMapping("/register")
  public ResponseEntity<?> register(@Valid @RequestBody Register input, HttpServletRequest req) {
    limiter.check(req.getRemoteAddr(), input.email());
    return ResponseEntity.status(201)
        .cacheControl(CacheControl.noStore())
        .body(auth.register(input.email(), input.password(), input.name(), input.locale()));
  }

  @PostMapping("/login")
  public ResponseEntity<?> login(@Valid @RequestBody Login input, HttpServletRequest req) {
    limiter.check(req.getRemoteAddr(), input.email());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(auth.login(input.email(), input.password()));
  }

  @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "customerSession")
  @GetMapping("/me")
  public ResponseEntity<?> me(@AuthenticationPrincipal CustomerAuthService.Customer customer) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(customer);
  }

  @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "customerSession")
  @PostMapping("/logout")
  public ResponseEntity<?> logout(@RequestHeader("Authorization") String token) {
    auth.logout(token.substring(7));
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }
}
