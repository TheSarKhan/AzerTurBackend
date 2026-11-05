package az.azertur.event;

import az.azertur.shared.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.util.List;
import java.util.Set;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Public, read-only: cities and published events. Device coordinates are used per request only. */
@RestController
public class EventController {
  private static final Set<String> CATEGORIES =
      Set.of("CONCERT", "FESTIVAL", "SPORT", "CULTURE", "MARKET", "FOOD", "FAMILY", "OTHER");
  private static final int PER_MINUTE = 120;
  private final EventService events;
  private final PublicRateLimiter limiter;

  public EventController(EventService events, PublicRateLimiter limiter) {
    this.events = events;
    this.limiter = limiter;
  }

  @GetMapping("/api/v1/cities")
  @Cacheable("cities-v1")
  public List<EventService.City> cities() {
    return List.copyOf(events.cities());
  }

  @GetMapping("/api/v1/events")
  public ResponseEntity<EventService.Page<EventService.EventView>> list(
      @RequestParam(required = false) String city,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Double lat,
      @RequestParam(required = false) Double lng,
      @RequestParam(required = false) Double radiusKm,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      HttpServletRequest req) {
    limiter.check(req, "events", PER_MINUTE);
    EventRules.checkLocation(lat, lng);
    if (category != null && !category.isBlank() && !CATEGORIES.contains(category))
      throw DomainException.bad("INVALID_CATEGORY");
    if (q != null && q.length() > 80) throw DomainException.bad("QUERY_TOO_LONG");
    if (page < 0 || page > 10_000 || size < 1 || size > 50) throw DomainException.bad("INVALID_PAGE");
    double radius = radiusKm == null ? 25 : radiusKm;
    if (!(radius >= 1 && radius <= 200)) throw DomainException.bad("INVALID_RADIUS");
    var result =
        events.listPublic(
            new EventService.PublicQuery(
                city, category, EventRules.parseBoundary(from, false),
                EventRules.parseBoundary(to, true), q, lat, lng, radius, page, size));
    // Location searches are never cached by browsers or proxies.
    return ResponseEntity.ok()
        .cacheControl(lat != null ? CacheControl.noStore() : CacheControl.maxAge(java.time.Duration.ofMinutes(1)).cachePublic())
        .body(result);
  }

  @GetMapping("/api/v1/events/{slug}")
  public ResponseEntity<EventService.EventDetail> detail(@PathVariable String slug, HttpServletRequest req) {
    limiter.check(req, "events", PER_MINUTE);
    if (slug.length() > 120) throw DomainException.missing();
    return events.detail(slug)
        .map(d -> ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(1)).cachePublic()).body(d))
        .orElseThrow(DomainException::missing);
  }
}
