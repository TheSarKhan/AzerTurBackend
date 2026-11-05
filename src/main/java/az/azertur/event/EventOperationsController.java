package az.azertur.event;

import az.azertur.shared.DomainException;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Office management of events. Reads, writes, publish and cancel need OPERATIONS; delete needs ADMIN. */
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "operationsAuth")
@RestController
@RequestMapping("/api/v1/operations/events")
public class EventOperationsController {
  private static final Set<String> STATUSES = Set.of("DRAFT", "PENDING_REVIEW", "PUBLISHED", "CANCELLED");
  private final EventService events;

  public EventOperationsController(EventService events) {
    this.events = events;
  }

  public record Note(String note) {}

  @GetMapping
  public EventService.Page<EventService.StaffEvent> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String city,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    if (status != null && !status.isBlank() && !STATUSES.contains(status))
      throw DomainException.bad("INVALID_STATUS");
    if (q != null && q.length() > 80) throw DomainException.bad("QUERY_TOO_LONG");
    if (page < 0 || page > 10_000 || size < 1 || size > 100) throw DomainException.bad("INVALID_PAGE");
    return events.listStaff(new EventService.StaffQuery(status, city, category, q, page, size));
  }

  @GetMapping("/{id}")
  public Map<String, Object> get(@PathVariable UUID id) {
    return Map.of("event", events.get(id), "audit", events.auditTrail(id));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public EventService.StaffEvent create(@RequestBody EventService.EventInput body, Principal actor) {
    return events.create(body, actor.getName());
  }

  @PostMapping("/{id}")
  public EventService.StaffEvent update(
      @PathVariable UUID id, @RequestBody EventService.EventInput body, Principal actor) {
    return events.update(id, body, actor.getName());
  }

  @PostMapping("/{id}/publish")
  public EventService.StaffEvent publish(@PathVariable UUID id, Principal actor) {
    return events.publish(id, actor.getName());
  }

  @PostMapping("/{id}/cancel")
  public EventService.StaffEvent cancel(
      @PathVariable UUID id, @RequestBody(required = false) Note body, Principal actor) {
    return events.cancel(id, actor.getName(), body == null ? null : body.note());
  }

  @PostMapping("/{id}/delete")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id, Principal actor) {
    events.delete(id, actor.getName());
  }
}
