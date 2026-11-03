package az.azertur.customer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AccountSecurityController {
  private final AccountSecurityService security;
  private final AuthRateLimiter limits;
  public AccountSecurityController(AccountSecurityService security,AuthRateLimiter limits){this.security=security;this.limits=limits;}
  public record Address(@NotBlank @Email @Size(max=254) String email){}
  public record Token(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String token){}
  public record Reset(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,
      @NotBlank @Size(min=12,max=72) String password,@NotBlank @Size(min=12,max=72) String confirmation){}
  @PostMapping("/forgot-password")
  public ResponseEntity<?> forgot(@Valid @RequestBody Address input,HttpServletRequest request){
    limits.check(request.getRemoteAddr(),"recover:"+input.email());security.forgot(input.email());
    return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Map.of("status","REQUEST_ACCEPTED"));
  }
  @PostMapping("/email-verification/request")
  public ResponseEntity<?> resend(@AuthenticationPrincipal CustomerAuthService.Customer c,HttpServletRequest request){
    limits.check(request.getRemoteAddr(),"verify:"+c.id());security.verification(c.id());
    return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Map.of("status","REQUEST_ACCEPTED"));
  }
  @PostMapping("/verify-email")
  public ResponseEntity<?> verify(@Valid @RequestBody Token input,HttpServletRequest request){
    limits.check(request.getRemoteAddr(),"link:"+input.token());security.verify(input.token());return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }
  @PostMapping("/reset-password")
  public ResponseEntity<?> reset(@Valid @RequestBody Reset input,HttpServletRequest request){
    limits.check(request.getRemoteAddr(),"link:"+input.token());security.reset(input.token(),input.password(),input.confirmation());return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }
  @GetMapping("/sessions") Object sessions(@AuthenticationPrincipal CustomerAuthService.Customer c,@RequestHeader("Authorization") String token){return security.sessions(c.id(),token.substring(7));}
  @PostMapping("/sessions/revoke-others") @ResponseStatus(HttpStatus.NO_CONTENT)
  void revokeOthers(@AuthenticationPrincipal CustomerAuthService.Customer c,@RequestHeader("Authorization") String token){security.revokeOthers(c.id(),token.substring(7));}
  @PostMapping("/sessions/{id}/revoke") @ResponseStatus(HttpStatus.NO_CONTENT)
  void revoke(@PathVariable UUID id,@AuthenticationPrincipal CustomerAuthService.Customer c,@RequestHeader("Authorization") String token){security.revoke(c.id(),id,token.substring(7));}
  @PostMapping("/logout-all") @ResponseStatus(HttpStatus.NO_CONTENT)
  void revokeAll(@AuthenticationPrincipal CustomerAuthService.Customer c){security.revokeAll(c.id());}
}
