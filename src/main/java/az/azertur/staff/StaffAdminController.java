package az.azertur.staff;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "operationsAuth")
@RestController
@RequestMapping("/api/v1/admin/staff")
public class StaffAdminController {
  public record Create(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 2, max = 120) String name,
      @NotBlank String role,
      @NotBlank @Size(max = 72) String password) {}

  public record Active(@NotNull Boolean active) {}

  public record Role(@NotBlank String role) {}

  public record Password(@NotBlank @Size(max = 72) String password) {}

  private final StaffAuthService staff;

  public StaffAdminController(StaffAuthService staff) {
    this.staff = staff;
  }

  @GetMapping
  public List<StaffAuthService.Account> list() {
    return staff.list();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public StaffAuthService.Account create(@Valid @RequestBody Create body) {
    return staff.create(body.email(), body.name(), body.role(), body.password());
  }

  @PostMapping("/{id}/active")
  public void active(
      @PathVariable UUID id,
      @Valid @RequestBody Active body,
      @AuthenticationPrincipal StaffAuthService.Staff actor) {
    staff.setActive(id, body.active(), actor.id());
  }

  @PostMapping("/{id}/role")
  public void role(
      @PathVariable UUID id,
      @Valid @RequestBody Role body,
      @AuthenticationPrincipal StaffAuthService.Staff actor) {
    staff.changeRole(id, body.role(), actor.id());
  }

  @PostMapping("/{id}/password")
  public void password(@PathVariable UUID id, @Valid @RequestBody Password body) {
    staff.resetPassword(id, body.password());
  }
}
