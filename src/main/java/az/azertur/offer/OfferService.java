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
  // One entry per place; several places can share a day. Older offers have no day number: every entry is
  // then a day of its own.
  public record Day(
      @NotBlank @Pattern(regexp = "Baku|Quba|Khinalug|Gabala|Sheki") String city,
      @NotBlank @Size(max = 1500) String description,
      @Min(1) @Max(14) Integer day) {
    public Day(String city, String description) {
      this(city, description, null);
    }
  }

  public record Line(
      // One line per service; a second line of the same kind gets a suffix, for example "guide-2".
      @NotBlank
          @Pattern(
              regexp =
                  "(transport|hotel|guide|transfer|intercity|activity|event|meals|tickets|photographer)(-[0-9]{1,2})?")
          String key,
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
      @NotNull @Size(max = 2000) String internalNote,
      // Which row of the route the service belongs to: 0 start, 1..days the days, days+1 the end.
      @Min(0) @Max(40) Integer row,
      // What the office pays and the margin on top (internal only). When a cost is given, the customer
      // price (unitPrice) is worked out from them on save; older offers have neither.
      @DecimalMin("0.000001") @DecimalMax("100000") @Digits(integer = 6, fraction = 6)
          BigDecimal costPrice,
      @DecimalMin("-100") @DecimalMax("300") @Digits(integer = 3, fraction = 2)
          BigDecimal marginPercent,
      // A customer price the office typed by hand. It wins over cost plus margin, and the margin is then
      // only worked out from it.
      @DecimalMin("0.000001") @DecimalMax("100000") @Digits(integer = 6, fraction = 6)
          BigDecimal fixedPrice) {}

  public record Draft(
      @Min(0) long version,
      @NotNull UUID sourceQuoteId,
      @NotBlank @Size(max = 160) String title,
      @NotNull @Pattern(regexp = "EUR|USD|AZN") String currency,
      @NotNull @Size(min = 1, max = 30) List<@Valid @NotNull Day> itinerary,
      @NotNull @Size(max = 1500) String returnRoute,
      @NotNull @Size(min = 1, max = 40) List<@Valid @NotNull Line> services,
      @NotNull @Size(max = 2000) String message,
      @NotNull @Size(max = 4000) String internalNote,
      @Pattern(regexp = "Baku|Quba|Khinalug|Gabala|Sheki") String startCity,
      @Pattern(regexp = "Baku|Quba|Khinalug|Gabala|Sheki") String endCity) {}

  /** "guide-2" is a second guide line; its kind is "guide". */
  public static String baseKey(String key) {
    return key.replaceAll("-[0-9]+$", "");
  }

  /** The customer price of a line that has a cost: cost plus the margin, to six decimals. */
  static Line priced(Line l) {
    if (l.fixedPrice() != null) {
      BigDecimal margin =
          l.costPrice() == null || l.costPrice().signum() == 0
              ? l.marginPercent()
              : l.fixedPrice()
                  .divide(l.costPrice(), 8, RoundingMode.HALF_UP)
                  .subtract(BigDecimal.ONE)
                  .movePointRight(2)
                  .setScale(2, RoundingMode.HALF_UP)
                  .max(BigDecimal.valueOf(-100))
                  .min(BigDecimal.valueOf(300));
      return new Line(
          l.key(), l.name(), l.quantity(), l.nights(), l.fixedPrice(), l.unit(), l.supplier(),
          l.location(), l.availability(), l.policy(), l.internalNote(), l.row(), l.costPrice(), margin,
          l.fixedPrice());
    }
    if (l.costPrice() == null) return l;
    BigDecimal margin = l.marginPercent() == null ? BigDecimal.valueOf(15) : l.marginPercent();
    BigDecimal price =
        l.costPrice()
            .multiply(BigDecimal.ONE.add(margin.movePointLeft(2)))
            .setScale(6, RoundingMode.HALF_UP);
    return new Line(
        l.key(), l.name(), l.quantity(), l.nights(), price, l.unit(), l.supplier(), l.location(),
        l.availability(), l.policy(), l.internalNote(), l.row(), l.costPrice(), margin, null);
  }

  public record Version(@Min(1) long version) {}

  public record PublicLine(
      String key,
      String name,
      int quantity,
      int nights,
      BigDecimal unitPrice,
      String unit,
      int total,
      String policy,
      // The place of the route the service belongs to: 0 start, 1..n the places, n+1 the end.
      Integer row) {
    public PublicLine(
        String key, String name, int quantity, int nights, BigDecimal unitPrice, String unit, int total, String policy) {
      this(key, name, quantity, nights, unitPrice, unit, total, policy, null);
    }
  }

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
      String sentAt,
      String startCity,
      String endCity) {
    public PublicOffer(
        UUID id, UUID bookingId, int revision, long draftVersion, String title, String currency, String date,
        int travelers, List<Day> itinerary, String returnRoute, List<PublicLine> services, int total,
        String message, String sentAt) {
      this(id, bookingId, revision, draftVersion, title, currency, date, travelers, itinerary, returnRoute,
          services, total, message, sentAt, null, null);
    }
  }

  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final boolean email;
  private final az.azertur.contract.ContractService contracts;

  public OfferService(
      JdbcTemplate db,
      ObjectMapper json,
      az.azertur.contract.ContractService contracts,
      @Value("${app.notifications.email-enabled:false}") boolean email) {
    this.db = db;
    this.json = json;
    this.contracts = contracts;
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
            "SELECT c.name,c.email,c.phone,c.locale FROM bookings b JOIN customers c ON c.id=b.customer_id WHERE b.id=?",
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

  private static final Map<String, String> DEFAULT_POLICY =
      Map.of(
          "az",
          "Ləğv və geri ödəniş şərtləri xidmətin təchizatçısından və ləğv vaxtından asılıdır. Komanda hər ləğv sorğusunu ayrıca nəzərdən keçirir.",
          "en",
          "Cancellation and refund terms depend on the service supplier and the time of cancellation. Our team reviews every cancellation request individually.",
          "ru",
          "Условия отмены и возврата зависят от поставщика услуги и времени отмены. Команда рассматривает каждый запрос на отмену отдельно.");

  /** Operators no longer type cancellation terms by hand; a blank one gets the standard wording in the customer's language. */
  private Draft withPolicies(UUID id, Draft d) {
    if (d.services().stream().noneMatch(l -> l.policy().isBlank())) return d;
    String locale =
        db.queryForList(
                "SELECT c.locale FROM bookings b JOIN customers c ON c.id=b.customer_id WHERE b.id=?",
                String.class,
                id)
            .stream()
            .findFirst()
            .orElse("en");
    String text = DEFAULT_POLICY.getOrDefault(locale, DEFAULT_POLICY.get("en"));
    var lines =
        d.services().stream()
            .map(
                l ->
                    l.policy().isBlank()
                        ? new Line(
                            l.key(),
                            l.name(),
                            l.quantity(),
                            l.nights(),
                            l.unitPrice(),
                            l.unit(),
                            l.supplier(),
                            l.location(),
                            l.availability(),
                            text,
                            l.internalNote(),
                            l.row(),
                            l.costPrice(),
                            l.marginPercent(),
                            l.fixedPrice())
                        : l)
            .toList();
    return new Draft(
        d.version(),
        d.sourceQuoteId(),
        d.title(),
        d.currency(),
        d.itinerary(),
        d.returnRoute(),
        lines,
        d.message(),
        d.internalNote(),
        d.startCity(),
        d.endCity());
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
    // Any destination may follow any other (the team arranges the transfers); the city names are
    // checked by the Day record, the same way the quote and the planner do it. Day numbers, when given,
    // start at 1 and go up one day at a time.
    if (d.itinerary().get(0).day() != null) {
      int previous = 0;
      for (var place : d.itinerary()) {
        if (place.day() == null
            || place.day() < Math.max(previous, 1)
            || place.day() > previous + 1)
          throw DomainException.bad("INVALID_ROUTE");
        previous = place.day();
      }
    }
    for (Line line : d.services()) {
      if (!keys.add(line.key())) throw DomainException.bad("DUPLICATE_SERVICE");
      String kind = baseKey(line.key());
      if (!kind.equals("hotel") && line.nights() != 1)
        throw DomainException.bad("INVALID_SERVICE_UNITS");
      var units =
          Map.of(
              "transport", "day",
              "hotel", "room",
              "guide", "day",
              "transfer", "transfer",
              "intercity", "transfer",
              "activity", "person",
              "event", "person",
              "meals", "person",
              "tickets", "person",
              "photographer", "day");
      if (!units.get(kind).equals(line.unit())) throw DomainException.bad("INVALID_SERVICE_UNITS");
      if (line.row() != null && line.row() > d.itinerary().size() + 1)
        throw DomainException.bad("INVALID_SERVICE_ROW");
      long amount = amount(line);
      if (amount < 1) throw DomainException.bad("INVALID_SERVICE_PRICE");
      total += amount;
      // The office no longer has to tick "available" and name a supplier on every line before sending.
    }
    if (total < 1 || total > 10000000) throw DomainException.bad("INVALID_OFFER_TOTAL");
    // The trip is a tour, so there is no separate return route to describe; only the message is needed.
    if (send && d.message().isBlank()) throw DomainException.conflict("OFFER_DETAILS_REQUIRED");
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
    d = withPolicies(id, d);
    d =
        new Draft(
            d.version(), d.sourceQuoteId(), d.title(), d.currency(), d.itinerary(), d.returnRoute(),
            d.services().stream().map(OfferService::priced).toList(), d.message(), d.internalNote(),
            d.startCity(), d.endCity());
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
            d.internalNote(),
            d.startCity(),
            d.endCity());
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
                        l.policy(),
                        // Older drafts have no row: transfers belong to the start, the rest to the first place.
                        Math.min(
                            Math.max(
                                l.row() != null
                                    ? l.row()
                                    : baseKey(l.key()).startsWith("transfer") ? 0 : 1,
                                0),
                            d.itinerary().size() + 1)))
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
        sentAt,
        d.startCity() == null ? "Baku" : d.startCity(),
        d.endCity() == null ? "Baku" : d.endCity());
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
    d = withPolicies(id, d);
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
    // The contract for this offer is prepared right away; the office reviews it and sends it separately.
    try {
      contracts.generate(id, actor);
    } catch (Exception e) {
      audit(id, actor, "CONTRACT_GENERATION_FAILED");
    }
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
