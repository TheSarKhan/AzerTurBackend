package az.azertur.booking;

import az.azertur.customer.CustomerAuthService.Customer;
import az.azertur.shared.DomainException;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {
  public record Booking(
      UUID id,
      UUID customerId,
      UUID quoteId,
      String status,
      String currency,
      int total,
      int deposit,
      int paid,
      LocalDate departure,
      String title,
      String quotePayload,
      String customerNote,
      String operatorNote,
      Instant createdAt) {}

  public record Request(
      UUID id,
      UUID bookingId,
      String kind,
      String message,
      String status,
      String response,
      Instant createdAt) {}

  private final JdbcTemplate db;
  private final boolean demoPayments;
  private final boolean emailEnabled;
  private final RowMapper<Booking> mapper =
      (r, n) ->
          new Booking(
              r.getObject("id", UUID.class),
              r.getObject("customer_id", UUID.class),
              r.getObject("quote_id", UUID.class),
              r.getString("status"),
              r.getString("currency"),
              r.getInt("total"),
              r.getInt("deposit"),
              r.getInt("paid"),
              r.getObject("departure", LocalDate.class),
              r.getString("title"),
              r.getString("quote_payload"),
              r.getString("customer_note"),
              r.getString("operator_note"),
              r.getTimestamp("created_at").toInstant());

  public BookingService(
      JdbcTemplate db,
      @Value("${app.demo-payments:true}") boolean demoPayments,
      @Value("${app.notifications.email-enabled:false}") boolean emailEnabled) {
    this.db = db;
    this.demoPayments = demoPayments;
    this.emailEnabled = emailEnabled;
  }

  public List<Booking> list(UUID customer) {
    return db.query(
        "SELECT * FROM bookings WHERE customer_id=? ORDER BY created_at DESC", mapper, customer);
  }

  public List<Booking> operations() {
    return db.query("SELECT * FROM bookings ORDER BY created_at DESC LIMIT 200", mapper);
  }

  private Booking lock(UUID id) {
    return db.query("SELECT * FROM bookings WHERE id=? FOR UPDATE", mapper, id).stream()
        .findFirst()
        .orElseThrow(DomainException::missing);
  }

  private Booking get(UUID id) {
    return db.query("SELECT * FROM bookings WHERE id=?", mapper, id).stream()
        .findFirst()
        .orElseThrow(DomainException::missing);
  }

  private void own(Booking booking, UUID customer) {
    if (!booking.customerId().equals(customer)) throw DomainException.missing();
  }

  @Transactional
  public Booking create(Customer customer, UUID quoteId, String note) {
    var existing =
        db.query(
            "SELECT * FROM bookings WHERE customer_id=? AND original_quote_id=?",
            mapper,
            customer.id(),
            quoteId);
    if (!existing.isEmpty()) return existing.getFirst();
    var priced =
        db.queryForList(
            "SELECT q.*,t.title_en FROM quotes q JOIN tours t ON t.slug=q.tour_slug WHERE q.id=?"
                + " FOR UPDATE OF q",
            quoteId);
    if (priced.isEmpty()) throw DomainException.missing();
    var q = priced.getFirst();
    if (((java.sql.Timestamp) q.get("expires_at")).toInstant().isBefore(Instant.now()))
      throw DomainException.conflict("QUOTE_EXPIRED");
    if (q.get("payload") == null) throw DomainException.conflict("REFRESH_QUOTE");
    // Re-check after acquiring the quote lock, including concurrent duplicate requests.
    existing =
        db.query(
            "SELECT * FROM bookings WHERE customer_id=? AND original_quote_id=?",
            mapper,
            customer.id(),
            quoteId);
    if (!existing.isEmpty()) return existing.getFirst();
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO"
            + " bookings(id,customer_id,quote_id,original_quote_id,status,currency,total,deposit,departure,title,quote_payload,customer_note)"
            + " VALUES(?,?,?,?,'PENDING_REVIEW',?,?,?,?,?,?,?)",
        id,
        customer.id(),
        quoteId,
        quoteId,
        q.get("currency"),
        q.get("total"),
        q.get("deposit"),
        q.get("departure"),
        q.get("title_en"),
        q.get("payload"),
        note);
    audit(id, customer.email(), "REQUESTED", note);
    notify(
        customer.id(),
        id,
        "BOOKING_REQUESTED",
        "Your reservation request is waiting for operator review.");
    return get(id);
  }

  @Transactional
  public Booking review(UUID id, boolean approve, String note, String actor) {
    Booking b = lock(id);
    String next = BookingWorkflow.review(b.status(), approve);
    db.update(
        "UPDATE bookings SET status=?,operator_note=?,updated_at=now() WHERE id=?", next, note, id);
    audit(id, actor, approve ? "APPROVED" : "REJECTED", note);
    notify(
        b.customerId(),
        id,
        next,
        approve
            ? "Your request was approved. Demo payment is now available."
            : "The operator could not approve your request. See the response in your trips.");
    return get(id);
  }

  @Transactional
  public Booking pay(UUID id, UUID customer, String key, String kind, boolean success) {
    Booking b = lock(id);
    own(b, customer);
    if (!demoPayments) throw DomainException.conflict("DEMO_PAYMENTS_DISABLED");
    var previous =
        db.queryForList(
            "SELECT * FROM booking_payments WHERE customer_id=? AND idempotency_key=?",
            customer,
            key);
    if (!previous.isEmpty()) {
      var payment = previous.getFirst();
      if (!id.equals(payment.get("booking_id")) || !kind.equals(payment.get("kind")))
        throw DomainException.conflict("IDEMPOTENCY_KEY_REUSED");
      return get(id);
    }
    if (db.queryForObject(
            "SELECT count(*) FROM booking_requests WHERE booking_id=? AND status='PENDING'",
            Long.class,
            id)
        > 0) throw DomainException.conflict("REQUEST_PENDING");
    int amount = BookingWorkflow.payable(b.status(), b.total(), b.deposit(), b.paid(), kind);
    if (!success) throw DomainException.conflict("DEMO_PAYMENT_FAILED");
    db.update(
        "INSERT INTO"
            + " booking_payments(id,booking_id,customer_id,idempotency_key,kind,amount,currency)"
            + " VALUES(?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        customer,
        key,
        kind,
        amount,
        b.currency());
    db.update(
        "UPDATE bookings SET paid=paid+?,status='CONFIRMED',updated_at=now() WHERE id=?",
        amount,
        id);
    audit(id, customer.toString(), "DEMO_" + kind, "Demo payment only");
    notify(
        customer,
        id,
        "PAYMENT_RECORDED",
        "Your demo payment was recorded. No real money was charged.");
    return get(id);
  }

  public List<Request> requests(UUID customer) {
    return db.query(
        "SELECT * FROM booking_requests WHERE customer_id=? ORDER BY created_at DESC",
        requestMapper(),
        customer);
  }

  public List<Request> operationsRequests() {
    return db.query(
        "SELECT * FROM booking_requests ORDER BY created_at DESC LIMIT 200", requestMapper());
  }

  private RowMapper<Request> requestMapper() {
    return (r, n) ->
        new Request(
            r.getObject("id", UUID.class),
            r.getObject("booking_id", UUID.class),
            r.getString("kind"),
            r.getString("message"),
            r.getString("status"),
            r.getString("response"),
            r.getTimestamp("created_at").toInstant());
  }

  @Transactional
  public Request request(UUID id, UUID customer, String kind, String message) {
    Booking b = lock(id);
    own(b, customer);
    if (List.of("CANCELLED", "REJECTED").contains(b.status()))
      throw DomainException.conflict("REQUEST_NOT_ALLOWED");
    UUID request = UUID.randomUUID();
    db.update(
        "INSERT INTO booking_requests(id,booking_id,customer_id,kind,message) VALUES(?,?,?,?,?)",
        request,
        id,
        customer,
        kind,
        message);
    audit(id, customer.toString(), "REQUEST_" + kind, message);
    notify(
        customer,
        id,
        "REQUEST_RECEIVED",
        "Your change or cancellation request is waiting for office review.");
    return requests(customer).stream()
        .filter(r -> r.id().equals(request))
        .findFirst()
        .orElseThrow();
  }

  @Transactional
  public void resolve(
      UUID request, boolean accept, String response, String actor, UUID replacementQuote) {
    var rows = db.queryForList("SELECT * FROM booking_requests WHERE id=?", request);
    if (rows.isEmpty()) throw DomainException.missing();
    var r = rows.getFirst();
    UUID booking = (UUID) r.get("booking_id");
    Booking b = lock(booking);
    if (!"PENDING".equals(r.get("status"))) throw DomainException.conflict("ALREADY_RESOLVED");
    if (accept && List.of("CANCELLED", "REJECTED").contains(b.status()))
      throw DomainException.conflict("REQUEST_NOT_ALLOWED");
    if (accept && "CHANGE".equals(r.get("kind"))) {
      if (replacementQuote == null) throw DomainException.bad("REPLACEMENT_QUOTE_REQUIRED");
      var priced =
          db.queryForList(
              "SELECT q.*,t.title_en FROM quotes q JOIN tours t ON t.slug=q.tour_slug WHERE q.id=?"
                  + " FOR UPDATE OF q",
              replacementQuote);
      if (priced.isEmpty()) throw DomainException.missing();
      var q = priced.getFirst();
      if (q.get("payload") == null
          || ((java.sql.Timestamp) q.get("expires_at")).toInstant().isBefore(Instant.now()))
        throw DomainException.conflict("QUOTE_EXPIRED");
      if (b.paid() > 0 && !b.currency().equals(q.get("currency")))
        throw DomainException.conflict("PAID_CURRENCY_CANNOT_CHANGE");
      if (((Number) q.get("total")).intValue() < b.paid())
        throw DomainException.conflict("MANUAL_REFUND_REQUIRED");
      db.update(
          "UPDATE bookings SET"
              + " quote_id=?,currency=?,total=?,deposit=?,departure=?,title=?,quote_payload=?,operator_note=?,updated_at=now()"
              + " WHERE id=?",
          replacementQuote,
          q.get("currency"),
          q.get("total"),
          q.get("deposit"),
          q.get("departure"),
          q.get("title_en"),
          q.get("payload"),
          response,
          booking);
    }
    int updated =
        db.update(
            "UPDATE booking_requests SET status=?,response=?,resolved_at=now() WHERE id=? AND"
                + " status='PENDING'",
            accept ? "ACCEPTED" : "DECLINED",
            response,
            request);
    if (updated != 1) throw DomainException.conflict("ALREADY_RESOLVED");
    if (accept && "CANCEL".equals(r.get("kind")))
      db.update(
          "UPDATE bookings SET status='CANCELLED',operator_note=?,updated_at=now() WHERE id=?",
          response,
          booking);
    audit(booking, actor, "RESOLVED_" + r.get("kind"), response);
    notify(
        b.customerId(),
        booking,
        "REQUEST_RESOLVED",
        "The operator responded to your request. Open your trips to review it.");
  }

  public List<Map<String, Object>> audit(UUID id) {
    return db.queryForList(
        "SELECT actor,action,note,created_at FROM booking_audit WHERE booking_id=? ORDER BY"
            + " created_at",
        id);
  }

  private void audit(UUID booking, String actor, String action, String note) {
    db.update(
        "INSERT INTO booking_audit(id,booking_id,actor,action,note) VALUES(?,?,?,?,?)",
        UUID.randomUUID(),
        booking,
        actor,
        action,
        note);
  }

  private void notify(UUID customer, UUID booking, String event, String body) {
    for (String channel : List.of("EMAIL", "WHATSAPP", "PUSH"))
      db.update(
          "INSERT INTO notification_outbox(id,customer_id,booking_id,channel,event,body,status)"
              + " VALUES(?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          customer,
          booking,
          channel,
          event,
          body,
          channel.equals("EMAIL") && emailEnabled ? "QUEUED" : "WAITING_CONFIGURATION");
  }
}
