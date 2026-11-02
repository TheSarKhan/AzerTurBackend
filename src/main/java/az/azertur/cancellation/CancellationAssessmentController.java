package az.azertur.cancellation;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/operations/requests/{id}/cancellation-assessment")
public class CancellationAssessmentController {
 private final CancellationAssessmentService service;
 public CancellationAssessmentController(CancellationAssessmentService service) { this.service=service; }
 @GetMapping public Object get(@PathVariable UUID id) { return service.get(id); }
 @PostMapping public Object review(@PathVariable UUID id,@Valid @RequestBody CancellationAssessmentService.Assessment body,Principal user) { return service.review(id,body,user.getName()); }
 public record Version(@jakarta.validation.constraints.Min(1) long version) {}
 @PostMapping("/approve") public Object approve(@PathVariable UUID id,@Valid @RequestBody Version body,Principal user) { return service.approve(id,body.version(),user.getName()); }
}
