package az.azertur.operations;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Statistics for the operator overview. Every figure is returned for the selected period and for the
 * period of the same length right before it, so the screen can show the change. Money figures are only
 * included for staff who hold the finance authority.
 */
@RestController
@RequestMapping("/api/v1/operations")
public class DashboardController {
  private static final List<Integer> PERIODS = List.of(7, 30, 90, 365);
  /** Same display rates as the website: 1 EUR = 1.87 AZN = 1.1 USD. */
  private static final String AZN_EQUIVALENT =
      "(amount_minor / 100.0) * CASE currency WHEN 'AZN' THEN 1 WHEN 'EUR' THEN 1.87 WHEN 'USD' THEN 1.87 / 1.1 ELSE 0 END";

  private final JdbcTemplate db;

  public DashboardController(JdbcTemplate db) {
    this.db = db;
  }

  private boolean finance(Principal p) {
    return p instanceof Authentication a
        && a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_FINANCE"));
  }

  private static double number(Object value) {
    return value == null ? 0 : ((Number) value).doubleValue();
  }

  @GetMapping("/dashboard")
  public ResponseEntity<Map<String, Object>> dashboard(
      @RequestParam(defaultValue = "30") int days, Principal actor) {
    int period = PERIODS.contains(days) ? days : 30;
    var out = new LinkedHashMap<String, Object>();
    out.put("days", period);

    // Window boundaries live in the database so every query uses the same instants.
    String window =
        "WITH w AS (SELECT now() - (?::int * interval '1 day') AS s,"
            + " now() - (2 * ?::int * interval '1 day') AS p) ";

    var requests =
        db.queryForMap(
            window
                + """
        SELECT
         count(*) FILTER (WHERE b.created_at >= w.s) AS created,
         count(*) FILTER (WHERE b.created_at >= w.p AND b.created_at < w.s) AS created_prev,
         count(*) FILTER (WHERE b.created_at >= w.s AND b.status = 'CONFIRMED') AS confirmed,
         count(*) FILTER (WHERE b.created_at >= w.p AND b.created_at < w.s AND b.status = 'CONFIRMED') AS confirmed_prev,
         count(*) FILTER (WHERE b.created_at >= w.s AND b.status IN ('REJECTED','CANCELLED')) AS lost,
         count(*) FILTER (WHERE b.created_at >= w.p AND b.created_at < w.s AND b.status IN ('REJECTED','CANCELLED')) AS lost_prev
        FROM bookings b, w WHERE b.created_at >= w.p
        """,
            period,
            period);
    out.put("requests", requests);

    var statuses = new LinkedHashMap<String, Object>();
    for (var row : db.queryForList("SELECT status, count(*) AS n FROM bookings GROUP BY status"))
      statuses.put((String) row.get("status"), row.get("n"));
    out.put("statuses", statuses);

    out.put(
        "requestSeries",
        db.queryForList(
            """
        WITH today AS (SELECT date_trunc('day', now() AT TIME ZONE 'Asia/Baku') AS d)
        SELECT to_char(g.d, 'YYYY-MM-DD') AS day, count(b.id) AS n
        FROM today, generate_series(today.d - (?::int - 1) * interval '1 day', today.d, interval '1 day') AS g(d)
        LEFT JOIN bookings b ON date_trunc('day', b.created_at AT TIME ZONE 'Asia/Baku') = g.d
        GROUP BY g.d ORDER BY g.d
        """,
            period));

    out.put(
        "topTours",
        db.queryForList(
            window
                + """
        SELECT b.title, count(*) AS n FROM bookings b, w
        WHERE b.created_at >= w.s GROUP BY b.title ORDER BY n DESC, b.title LIMIT 5
        """,
            period,
            period));

    out.put(
        "customers",
        db.queryForMap(
            window
                + """
        SELECT
         (SELECT count(*) FROM customers) AS total,
         (SELECT count(*) FROM customers c, w WHERE c.created_at >= w.s) AS created,
         (SELECT count(*) FROM customers c, w WHERE c.created_at >= w.p AND c.created_at < w.s) AS created_prev,
         (SELECT count(*) FROM (SELECT customer_id FROM bookings GROUP BY customer_id HAVING count(*) > 1) r) AS repeat
        """,
            period,
            period));

    out.put(
        "messages",
        db.queryForMap(
            window
                + """
        SELECT
         count(*) FILTER (WHERE m.created_at >= w.s) AS created,
         count(*) FILTER (WHERE m.created_at >= w.p AND m.created_at < w.s) AS created_prev,
         count(*) FILTER (WHERE m.state = 'NEW') AS open
        FROM contact_messages m, w
        """,
            period,
            period));

    // What needs a person right now, independent of the selected period.
    var attention = new LinkedHashMap<String, Object>();
    attention.put(
        "unassigned",
        db.queryForObject(
            "SELECT count(*) FROM bookings WHERE status = 'PENDING_REVIEW' AND assigned_to IS NULL",
            Long.class));
    attention.put(
        "changeRequests",
        db.queryForObject("SELECT count(*) FROM booking_requests WHERE status = 'PENDING'", Long.class));
    attention.put(
        "refunds",
        db.queryForObject(
            "SELECT count(*) FROM refund_tracking WHERE state IN ('PENDING','PROCESSING')",
            Long.class));
    attention.put(
        "messages",
        db.queryForObject("SELECT count(*) FROM contact_messages WHERE state = 'NEW'", Long.class));
    out.put("attention", attention);

    // Null for staff without the finance authority, so the screen knows to hide the money section.
    out.put("finance", finance(actor) ? financeBlock(period, window) : null);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
  }

  private Map<String, Object> financeBlock(int period, String window) {
    var finance = new LinkedHashMap<String, Object>();
    var totals =
        db.queryForMap(
            window
                + "SELECT coalesce(sum("
                + AZN_EQUIVALENT
                + ") FILTER (WHERE paid_at >= w.s), 0) AS received,"
                + " coalesce(sum("
                + AZN_EQUIVALENT
                + ") FILTER (WHERE paid_at >= w.p AND paid_at < w.s), 0) AS received_prev,"
                + " count(*) FILTER (WHERE paid_at >= w.s) AS payments,"
                + " count(*) FILTER (WHERE paid_at >= w.p AND paid_at < w.s) AS payments_prev"
                + " FROM staged_payment_requests, w WHERE status = 'PAID' AND paid_at >= w.p",
            period,
            period);
    finance.put("totals", totals);

    finance.put(
        "open",
        db.queryForMap(
            "SELECT count(*) AS n, coalesce(sum("
                + AZN_EQUIVALENT
                + "), 0) AS amount FROM staged_payment_requests WHERE status = 'OPEN'"));

    var byCurrency = new LinkedHashMap<String, Object>();
    for (var row :
        db.queryForList(
            window
                + "SELECT currency, sum(amount_minor) AS minor FROM staged_payment_requests, w"
                + " WHERE status = 'PAID' AND paid_at >= w.s GROUP BY currency ORDER BY currency",
            period,
            period))
      byCurrency.put((String) row.get("currency"), number(row.get("minor")) / 100.0);
    finance.put("byCurrency", byCurrency);

    finance.put(
        "methods",
        db.queryForList(
            window
                + "SELECT payment_method AS method, count(*) AS n, sum("
                + AZN_EQUIVALENT
                + ") AS amount FROM staged_payment_requests, w"
                + " WHERE status = 'PAID' AND paid_at >= w.s GROUP BY payment_method ORDER BY amount DESC",
            period,
            period));

    finance.put(
        "series",
        db.queryForList(
            "WITH today AS (SELECT date_trunc('day', now() AT TIME ZONE 'Asia/Baku') AS d)"
                + " SELECT to_char(g.d, 'YYYY-MM-DD') AS day, coalesce(sum("
                + AZN_EQUIVALENT.replace("amount_minor", "r.amount_minor").replace("currency", "r.currency")
                + "), 0) AS amount"
                + " FROM today, generate_series(today.d - (?::int - 1) * interval '1 day', today.d, interval '1 day') AS g(d)"
                + " LEFT JOIN staged_payment_requests r ON r.status = 'PAID'"
                + " AND date_trunc('day', r.paid_at AT TIME ZONE 'Asia/Baku') = g.d"
                + " GROUP BY g.d ORDER BY g.d",
            period));
    return finance;
  }
}
