package az.azertur.offer;

import az.azertur.shared.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class OfferService {
  public record Day(
      @NotBlank @Pattern(regexp = "Baku|Quba|Khinalug|Gabala|Sheki") String city,
      @NotBlank @Size(max = 1500) String description) {}

  public record Line(
      @NotBlank @Pattern(regexp = "transport|hotel|guide|transfer|activity") String key,
      @NotBlank @Size(max = 160) String name,
      @Min(1) @Max(168) int quantity,
      @Min(1) @Max(14) int nights,
      @NotNull @DecimalMin("0.000001") @DecimalMax("100000") @Digits(integer = 6, fraction = 6)
          BigDecimal unitPrice,
      @NotBlank @Pattern(regexp = "day|room|transfer|person") String unit,
      @NotNull @Size(max = 200) String supplier,
      @NotNull @Size(max = 100) String location,
      @NotNull @Pattern(regexp = "NOT_CHECKED|CONFIRMED|UNAVAILABLE") String availability,
      @NotNull @Size(max = 4000) String policy,
      @NotNull @Size(max = 2000) String internalNote) {}

  public record Draft(
      @Min(0) long version,
      @NotNull UUID sourceQuoteId,
      @NotBlank @Size(max = 160) String title,
      @NotNull @Pattern(regexp = "EUR|USD|AZN") String currency,
      @NotNull @Size(min = 1, max = 14) List<@Valid @NotNull Day> itinerary,
      @NotNull @Size(max = 1500) String returnRoute,
      @NotNull @Size(min = 1, max = 5) List<@Valid @NotNull Line> services,
      @NotNull @Size(max = 2000) String message,
      @NotNull @Size(max = 4000) String internalNote) {}

  public record Version(@Min(1) long version) {}

  public record PublicLine(
      String key,
      String name,
      int quantity,
      int nights,
      BigDecimal unitPrice,
      String unit,
      int total,
      String policy) {}

  public record PublicOffer(
      UUID id,
      UUID bookingId,
      int revision,
      long draftVersion,
      String title,
      String currency,
      String date,
      int travelers,
      List<Day> itinerary,
      String returnRoute,
      List<PublicLine> services,
      int total,
      String message,
      String sentAt) {}

  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final boolean email;

  public OfferService(
      JdbcTemplate db,
      ObjectMapper json,
      @Value("${app.notifications.email-enabled:false}") boolean email) {
    this.db = db;
    this.json = json;
    this.email = email;
  }

  private Map<String, Object> lock(UUID id) {
    return db.queryForList("SELECT * FROM bookings WHERE id=? FOR UPDATE", id).stream()
        .findFirst()
        .orElseThrow(DomainException::missing);
  }

  private void editable(Map<String, Object> b) {
    if(db.queryForObject("SELECT count(*) FROM cancelled_services WHERE booking_id=?",Integer.class,b.get("id"))>0) throw DomainException.conflict("PARTIAL_CANCELLATION_REQUIRES_MANUAL_REVIEW");
    if (!b.get("status").equals("PENDING_REVIEW") || ((Number) b.get("paid")).intValue() != 0 || db.queryForObject("SELECT count(*) FROM staged_payment_requests WHERE booking_id=? AND status='PAID'",Integer.class,b.get("id"))>0)
      throw DomainException.conflict("OFFER_NOT_EDITABLE");
  }

  public Object customerInfo(UUID id) {
    return db
        .queryForList(
            "SELECT c.name,c.email,c.locale FROM bookings b JOIN customers c ON c.id=b.customer_id WHERE b.id=?",
            id)
        .stream()
        .findFirst()
        .orElseThrow(DomainException::missing);
  }

  public Object get(UUID id) {
    if (db.queryForObject("SELECT count(*) FROM bookings WHERE id=?", Integer.class, id) == 0)
      throw DomainException.missing();
    return db
        .queryForList(
            "SELECT version,payload,preview_version,updated_at FROM offer_drafts WHERE booking_id=?",
            id)
        .stream()
        .findFirst()
        .map(
            r ->
                (Object)
                    Map.of(
                        "draft",
                        json.readValue((String) r.get("payload"), Draft.class),
                        "previewVersion",
                        r.get("preview_version"),
                        "sentVersion",
                        db.queryForObject(
                            "SELECT coalesce(max(draft_version),0) FROM offer_revisions WHERE booking_id=?",
                            Long.class,
                            id),
                        "updatedAt",
                        r.get("updated_at").toString()))
        .orElse(null);
  }

  private Draft draft(UUID id) {
    return db.queryForList("SELECT payload FROM offer_drafts WHERE booking_id=?", id).stream()
        .findFirst()
        .map(r -> json.readValue((String) r.get("payload"), Draft.class))
        .orElseThrow(DomainException::missing);
  }

  public static int amount(Line line) {
    return line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())).multiply(BigDecimal.valueOf(line.nights())).setScale(0,RoundingMode.HALF_UP).intValueExact();
  }

  private void validate(Draft d, Map<String, Object> b, boolean send) {
    if (!d.sourceQuoteId().equals(b.get("quote_id")))
      throw DomainException.conflict("SOURCE_CHANGED");
    if (!d.currency().equals(b.get("currency"))) throw DomainException.bad("CURRENCY_MISMATCH");
    var keys = new HashSet<String>();
    long total = 0;
    var routes =
        Map.of(
            "Baku",
            List.of("Baku", "Quba", "Gabala"),
            "Quba",
            List.of("Quba", "Baku", "Khinalug"),
            "Khinalug",
            List.of("Khinalug", "Quba"),
            "Gabala",
            List.of("Gabala", "Baku", "Sheki"),
            "Sheki",
            List.of("Sheki", "Gabala"));
    for (int i = 1; i < d.itinerary().size(); i++)
      if (!routes.get(d.itinerary().get(i - 1).city()).contains(d.itinerary().get(i).city()))
        throw DomainException.bad("INVALID_ROUTE");
    for (Line line : d.services()) {
      if (!keys.add(line.key())) throw DomainException.bad("DUPLICATE_SERVICE");
      if (!line.key().equals("hotel") && line.nights() != 1)
        throw DomainException.bad("INVALID_SERVICE_UNITS");
      var units =
          Map.of(
              "transport",
              "day",
              "hotel",
              "room",
              "guide",
              "day",
              "transfer",
              "transfer",
              "activity",
              "person");
      if (!units.get(line.key()).equals(line.unit()))
        throw DomainException.bad("INVALID_SERVICE_UNITS");
      long amount = amount(line);
      if (amount < 1) throw DomainException.bad("INVALID_SERVICE_PRICE");
      total += amount;
      if (send
          && (!line.availability().equals("CONFIRMED")
              || line.supplier().isBlank()
              || line.policy().isBlank()))
        throw DomainException.conflict("SERVICE_CHECKS_REQUIRED");
    }
    if (total < 1 || total > 10000000) throw DomainException.bad("INVALID_OFFER_TOTAL");
    if (send && (d.message().isBlank() || d.returnRoute().isBlank()))
      throw DomainException.conflict("OFFER_DETAILS_REQUIRED");
  }

  private void audit(UUID id, String actor, String action) {
    db.update(
        "INSERT INTO booking_audit(id,booking_id,actor,action,note) VALUES(?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        actor,
        action,
        "");
  }

  @Transactional
  public Draft save(UUID id, Draft d, String actor) {
    var b = lock(id);
    editable(b);
    validate(d, b, false);
    long current =
        db.queryForList("SELECT version FROM offer_drafts WHERE booking_id=?", id).stream()
            .findFirst()
            .map(r -> ((Number) r.get("version")).longValue())
            .orElse(0L);
    if (current != d.version()) throw DomainException.conflict("STALE_OFFER");
    var next =
        new Draft(
            current + 1,
            d.sourceQuoteId(),
            d.title().trim(),
            d.currency(),
            d.itinerary(),
            d.returnRoute(),
            d.services(),
            d.message(),
            d.internalNote());
    db.update(
        "INSERT INTO offer_drafts(booking_id,version,payload,updated_by) VALUES(?,?,?,?) ON CONFLICT(booking_id) DO UPDATE SET version=excluded.version,payload=excluded.payload,preview_version=0,updated_by=excluded.updated_by,updated_at=now()",
        id,
        next.version(),
        json.writeValueAsString(next),
        actor);
    audit(id, actor, "OFFER_DRAFT_SAVED");
    return next;
  }

  private PublicOffer project(
      UUID id, Draft d, Map<String, Object> b, UUID revisionId, int revision, String sentAt) {
    List<PublicLine> lines =
        d.services().stream()
            .map(
                l ->
                    new PublicLine(
                        l.key(),
                        l.name(),
                        l.quantity(),
                        l.nights(),
                        l.unitPrice(),
                        l.unit(),
                        amount(l),
                        l.policy()))
            .toList();
    var original = json.readTree((String) b.get("quote_payload"));
    return new PublicOffer(
        revisionId,
        id,
        revision,
        d.version(),
        d.title(),
        d.currency(),
        b.get("departure").toString(),
        original.path("input").path("pax").asInt(),
        d.itinerary(),
        d.returnRoute(),
        lines,
        lines.stream().mapToInt(PublicLine::total).sum(),
        d.message(),
        sentAt);
  }

  @Transactional
  public PublicOffer preview(UUID id, long version) {
    var b = lock(id);
    editable(b);
    var d = draft(id);
    if (version != d.version()) throw DomainException.conflict("STALE_OFFER");
    db.update("UPDATE offer_drafts SET preview_version=? WHERE booking_id=?", version, id);
    return project(id, d, b, null, 0, null);
  }

  @Transactional
  public PublicOffer send(UUID id, long version, String actor) {
    var b = lock(id);
    var prior =
        db.queryForList(
            "SELECT payload,source_quote_id FROM offer_revisions WHERE booking_id=? AND draft_version=?",
            id,
            version);
    if (!prior.isEmpty()) {
      if (!b.get("quote_id").equals(prior.get(0).get("source_quote_id")))
        throw DomainException.conflict("SOURCE_CHANGED");
      return json.readValue((String) prior.get(0).get("payload"), PublicOffer.class);
    }
    editable(b);
    var d = draft(id);
    if (d.version() != version) throw DomainException.conflict("STALE_OFFER");
    if (db.queryForObject(
            "SELECT count(*) FROM booking_requests WHERE booking_id=? AND status='PENDING'",
            Integer.class,
            id)
        > 0) throw DomainException.conflict("REQUEST_PENDING");
    if (db.queryForObject(
            "SELECT preview_version FROM offer_drafts WHERE booking_id=?", Long.class, id)
        != version) throw DomainException.conflict("PREVIEW_REQUIRED");
    validate(d, b, true);
    int revision =
        db.queryForObject(
            "SELECT coalesce(max(revision),0)+1 FROM offer_revisions WHERE booking_id=?",
            Integer.class,
            id);
    var result = project(id, d, b, UUID.randomUUID(), revision, Instant.now().toString());
    db.update(
        "INSERT INTO offer_revisions(id,booking_id,revision,draft_version,payload,sent_by,source_quote_id) VALUES(?,?,?,?,?,?,?)",
        result.id(),
        id,
        revision,
        version,
        json.writeValueAsString(result),
        actor,
        d.sourceQuoteId());
    audit(id, actor, "OFFER_SENT");
    for (String channel : List.of("EMAIL", "WHATSAPP", "PUSH"))
      db.update(
          "INSERT INTO notification_outbox(id,customer_id,booking_id,channel,event,body,status) VALUES(?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          b.get("customer_id"),
          id,
          channel,
          "OFFER_SENT",
          "A new offer is ready in your account. No payment is requested.",
          channel.equals("EMAIL") && email ? "QUEUED" : "WAITING_CONFIGURATION");
    return result;
  }

  public List<PublicOffer> customerOffers(UUID customer) {
    return db.query(
        "SELECT DISTINCT ON (o.booking_id) o.payload FROM offer_revisions o JOIN bookings b ON b.id=o.booking_id WHERE b.customer_id=? AND o.source_quote_id=b.quote_id ORDER BY o.booking_id,o.revision DESC",
        (r, n) -> json.readValue(r.getString("payload"), PublicOffer.class),
        customer);
  }
}
