package az.azertur.event;

import az.azertur.shared.DomainException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {
  static final int MAX_NEAR_CANDIDATES = 1000;
  private static final Set<String> CATEGORIES =
      Set.of("CONCERT", "FESTIVAL", "SPORT", "CULTURE", "MARKET", "FOOD", "FAMILY", "OTHER");

  public record City(
      String slug, String nameEn, String nameRu, String nameAz, double latitude, double longitude)
      implements Serializable {}

  public record EventView(
      String slug,
      String titleEn,
      String titleRu,
      String titleAz,
      String category,
      String citySlug,
      String cityNameEn,
      String cityNameRu,
      String cityNameAz,
      String venueName,
      Instant startsAt,
      Instant endsAt,
      boolean timeKnown,
      boolean free,
      Integer priceFromMinor,
      String currency,
      String image,
      double latitude,
      double longitude,
      Double distanceKm) {}

  public record EventDetail(
      EventView event,
      String descriptionEn,
      String descriptionRu,
      String descriptionAz,
      String address,
      String ticketUrl) {}

  /** Everything staff see, including the fields that never reach customers. */
  public record StaffEvent(
      UUID id,
      String slug,
      String titleEn,
      String titleRu,
      String titleAz,
      String descriptionEn,
      String descriptionRu,
      String descriptionAz,
      String category,
      String citySlug,
      String venueName,
      String address,
      double latitude,
      double longitude,
      Instant startsAt,
      Instant endsAt,
      boolean timeKnown,
      boolean free,
      Integer priceFromMinor,
      String currency,
      String ticketUrl,
      String image,
      String status,
      String source,
      String externalSource,
      boolean adminLocked,
      String sourceNote,
      int version,
      String createdBy,
      String updatedBy,
      Instant createdAt,
      Instant updatedAt) {}

  public record Page<T>(List<T> items, int page, int size, long total) {}

  public record PublicQuery(
      String city,
      String category,
      Instant from,
      Instant to,
      String q,
      Double lat,
      Double lng,
      double radiusKm,
      int page,
      int size) {}

  public record StaffQuery(
      String status, String city, String category, String q, int page, int size) {}

  public record EventInput(
      String slug,
      String titleEn,
      String titleRu,
      String titleAz,
      String descriptionEn,
      String descriptionRu,
      String descriptionAz,
      String category,
      String citySlug,
      String venueName,
      String address,
      Double latitude,
      Double longitude,
      Instant startsAt,
      Instant endsAt,
      Boolean timeKnown,
      Boolean free,
      Integer priceFromMinor,
      String currency,
      String ticketUrl,
      String image,
      String sourceNote,
      Integer version) {}

  private final JdbcTemplate db;

  public EventService(JdbcTemplate db) {
    this.db = db;
  }

  // ---------------------------------------------------------------- public reads

  public List<City> cities() {
    return db.query(
        "SELECT * FROM cities ORDER BY name_en",
        (r, n) ->
            new City(
                r.getString("slug"),
                r.getString("name_en"),
                r.getString("name_ru"),
                r.getString("name_az"),
                r.getDouble("latitude"),
                r.getDouble("longitude")));
  }

  private static final String SELECT =
      "SELECT e.*, c.name_en city_en, c.name_ru city_ru, c.name_az city_az FROM events e JOIN cities"
          + " c ON c.slug=e.city_slug";

  private static String like(String q) {
    return "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.from(i);
  }

  private static void textFilter(StringBuilder where, List<Object> args, String q) {
    if (q == null || q.isBlank()) return;
    String p = like(q);
    where.append(
        " AND (e.title_en ILIKE ? ESCAPE '\\' OR e.title_az ILIKE ? ESCAPE '\\' OR"
            + " e.title_ru ILIKE ? ESCAPE '\\' OR e.venue_name ILIKE ? ESCAPE '\\')");
    for (int i = 0; i < 4; i++) args.add(p);
  }

  public Page<EventView> listPublic(PublicQuery q) {
    StringBuilder where = new StringBuilder(" WHERE e.status='PUBLISHED' AND e.ends_at >= ?");
    List<Object> args = new ArrayList<>();
    args.add(ts(q.from() != null ? q.from() : Instant.now()));
    if (q.to() != null) {
      where.append(" AND e.starts_at < ?");
      args.add(ts(q.to()));
    }
    if (q.city() != null && !q.city().isBlank()) {
      where.append(" AND e.city_slug = ?");
      args.add(q.city());
    }
    if (q.category() != null && !q.category().isBlank()) {
      where.append(" AND e.category = ?");
      args.add(q.category());
    }
    textFilter(where, args, q.q());
    if (q.lat() != null) return nearMe(q, where, args);
    long total = db.queryForObject("SELECT count(*) FROM events e" + where, Long.class, args.toArray());
    List<Object> page = new ArrayList<>(args);
    page.add(q.size());
    page.add((long) q.page() * q.size());
    var items =
        db.query(
            SELECT + where + " ORDER BY e.starts_at, e.id LIMIT ? OFFSET ?",
            viewMapper(null),
            page.toArray());
    return new Page<>(items, q.page(), q.size(), total);
  }

  /** A box in SQL keeps the candidate set small; exact distances are then computed and sorted here. */
  private Page<EventView> nearMe(PublicQuery q, StringBuilder where, List<Object> args) {
    Geo.Box box = Geo.boundingBox(q.lat(), q.lng(), q.radiusKm());
    where.append(" AND e.latitude BETWEEN ? AND ? AND e.longitude BETWEEN ? AND ?");
    args.addAll(List.of(box.minLat(), box.maxLat(), box.minLng(), box.maxLng()));
    args.add(MAX_NEAR_CANDIDATES);
    var rows =
        db.query(
            SELECT + where + " ORDER BY e.starts_at, e.id LIMIT ?",
            (r, n) -> {
              EventView v = view(r, null);
              return new EventView(
                  v.slug(), v.titleEn(), v.titleRu(), v.titleAz(), v.category(), v.citySlug(),
                  v.cityNameEn(), v.cityNameRu(), v.cityNameAz(), v.venueName(), v.startsAt(),
                  v.endsAt(), v.timeKnown(), v.free(), v.priceFromMinor(), v.currency(), v.image(),
                  v.latitude(), v.longitude(),
                  round1(Geo.distanceKm(q.lat(), q.lng(), v.latitude(), v.longitude())));
            },
            args.toArray());
    var within =
        rows.stream()
            .filter(v -> v.distanceKm() <= q.radiusKm())
            .sorted(
                Comparator.comparingDouble(EventView::distanceKm)
                    .thenComparing(EventView::startsAt))
            .toList();
    int from = Math.min(within.size(), q.page() * q.size());
    int to = Math.min(within.size(), from + q.size());
    return new Page<>(within.subList(from, to), q.page(), q.size(), within.size());
  }

  private static double round1(double v) {
    return Math.round(v * 10) / 10.0;
  }

  public Optional<EventDetail> detail(String slug) {
    return db.query(
            SELECT + " WHERE e.slug = ? AND e.status = 'PUBLISHED'",
            (r, n) ->
                new EventDetail(
                    view(r, null),
                    r.getString("description_en"),
                    fallback(r.getString("description_ru"), r.getString("description_en")),
                    r.getString("description_az"),
                    r.getString("address"),
                    r.getString("ticket_url")),
            slug)
        .stream()
        .findFirst();
  }

  private RowMapper<EventView> viewMapper(Double distance) {
    return (r, n) -> view(r, distance);
  }

  private static String fallback(String value, String other) {
    return value == null || value.isBlank() ? other : value;
  }

  private static EventView view(ResultSet r, Double distance) throws SQLException {
    return new EventView(
        r.getString("slug"),
        r.getString("title_en"),
        fallback(r.getString("title_ru"), r.getString("title_en")),
        r.getString("title_az"),
        r.getString("category"),
        r.getString("city_slug"),
        r.getString("city_en"),
        r.getString("city_ru"),
        r.getString("city_az"),
        r.getString("venue_name"),
        r.getTimestamp("starts_at").toInstant(),
        r.getTimestamp("ends_at").toInstant(),
        r.getBoolean("time_known"),
        r.getBoolean("is_free"),
        (Integer) r.getObject("price_from_minor"),
        r.getString("currency"),
        r.getString("image"),
        r.getDouble("latitude"),
        r.getDouble("longitude"),
        distance);
  }

  // ---------------------------------------------------------------- staff

  private static StaffEvent staff(ResultSet r, int n) throws SQLException {
    return new StaffEvent(
        r.getObject("id", UUID.class),
        r.getString("slug"),
        r.getString("title_en"),
        r.getString("title_ru"),
        r.getString("title_az"),
        r.getString("description_en"),
        r.getString("description_ru"),
        r.getString("description_az"),
        r.getString("category"),
        r.getString("city_slug"),
        r.getString("venue_name"),
        r.getString("address"),
        r.getDouble("latitude"),
        r.getDouble("longitude"),
        r.getTimestamp("starts_at").toInstant(),
        r.getTimestamp("ends_at").toInstant(),
        r.getBoolean("time_known"),
        r.getBoolean("is_free"),
        (Integer) r.getObject("price_from_minor"),
        r.getString("currency"),
        r.getString("ticket_url"),
        r.getString("image"),
        r.getString("status"),
        r.getString("source"),
        r.getString("external_source"),
        r.getBoolean("admin_locked"),
        r.getString("source_note"),
        r.getInt("version"),
        r.getString("created_by"),
        r.getString("updated_by"),
        r.getTimestamp("created_at").toInstant(),
        r.getTimestamp("updated_at").toInstant());
  }

  public Page<StaffEvent> listStaff(StaffQuery q) {
    StringBuilder where = new StringBuilder(" WHERE true");
    List<Object> args = new ArrayList<>();
    if (q.status() != null && !q.status().isBlank()) {
      where.append(" AND e.status = ?");
      args.add(q.status());
    }
    if (q.city() != null && !q.city().isBlank()) {
      where.append(" AND e.city_slug = ?");
      args.add(q.city());
    }
    if (q.category() != null && !q.category().isBlank()) {
      where.append(" AND e.category = ?");
      args.add(q.category());
    }
    textFilter(where, args, q.q());
    long total = db.queryForObject("SELECT count(*) FROM events e" + where, Long.class, args.toArray());
    List<Object> page = new ArrayList<>(args);
    page.add(q.size());
    page.add((long) q.page() * q.size());
    var items =
        db.query(
            "SELECT e.* FROM events e" + where + " ORDER BY e.starts_at DESC, e.id LIMIT ? OFFSET ?",
            EventService::staff,
            page.toArray());
    return new Page<>(items, q.page(), q.size(), total);
  }

  public StaffEvent get(UUID id) {
    return db.query("SELECT e.* FROM events e WHERE e.id = ?", EventService::staff, id).stream()
        .findFirst()
        .orElseThrow(DomainException::missing);
  }

  private void audit(UUID event, String actor, String action, String note) {
    db.update(
        "INSERT INTO event_audit(id,event_id,actor,action,note) VALUES(?,?,?,?,?)",
        UUID.randomUUID(), event, actor, action, note);
  }

  /** Validates and normalises an input; returns the cleaned values in column order. */
  private Object[] cleaned(EventInput in) {
    String titleEn = EventRules.plain(in.titleEn()), titleAz = EventRules.plain(in.titleAz());
    if (titleEn == null || titleEn.isBlank() || titleAz == null || titleAz.isBlank())
      throw DomainException.bad("TITLE_REQUIRED");
    String titleRu = EventRules.plain(in.titleRu());
    String descEn = Optional.ofNullable(EventRules.plain(in.descriptionEn())).orElse("");
    String descAz = Optional.ofNullable(EventRules.plain(in.descriptionAz())).orElse("");
    String descRu = EventRules.plain(in.descriptionRu());
    String venue = EventRules.plain(in.venueName());
    if (venue == null || venue.isBlank()) throw DomainException.bad("VENUE_REQUIRED");
    String address = Optional.ofNullable(EventRules.plain(in.address())).orElse("");
    String note = Optional.ofNullable(EventRules.plain(in.sourceNote())).orElse("");
    for (String s : new String[] {titleEn, titleAz, titleRu, venue})
      if (s != null && s.length() > 160) throw DomainException.bad("TEXT_TOO_LONG");
    for (String s : new String[] {descEn, descAz, descRu})
      if (s != null && s.length() > 4000) throw DomainException.bad("TEXT_TOO_LONG");
    if (address.length() > 240 || note.length() > 500) throw DomainException.bad("TEXT_TOO_LONG");
    if (in.category() == null || !CATEGORIES.contains(in.category()))
      throw DomainException.bad("INVALID_CATEGORY");
    if (in.citySlug() == null
        || db.queryForObject("SELECT count(*) FROM cities WHERE slug=?", Integer.class, in.citySlug()) == 0)
      throw DomainException.bad("INVALID_CITY");
    if (in.latitude() == null || in.longitude() == null) throw DomainException.bad("INVALID_LOCATION");
    EventRules.checkLocation(in.latitude(), in.longitude());
    if (in.startsAt() == null || in.endsAt() == null || in.endsAt().isBefore(in.startsAt()))
      throw DomainException.bad("INVALID_DATE_RANGE");
    boolean free = Boolean.TRUE.equals(in.free());
    Integer price = free ? null : in.priceFromMinor();
    if (price != null && (price < 0 || price > 100_000_00)) throw DomainException.bad("INVALID_PRICE");
    String currency = in.currency() == null || in.currency().isBlank() ? "AZN" : in.currency();
    if (!Set.of("AZN", "EUR", "USD").contains(currency)) throw DomainException.bad("INVALID_CURRENCY");
    return new Object[] {
      titleEn, titleRu == null || titleRu.isBlank() ? null : titleRu, titleAz,
      descEn, descRu == null || descRu.isBlank() ? null : descRu, descAz,
      in.category(), in.citySlug(), venue, address,
      BigDecimal.valueOf(in.latitude()).setScale(6, RoundingMode.HALF_UP),
      BigDecimal.valueOf(in.longitude()).setScale(6, RoundingMode.HALF_UP),
      ts(in.startsAt()), ts(in.endsAt()), !Boolean.FALSE.equals(in.timeKnown()),
      free, price, currency,
      EventRules.https(in.ticketUrl(), "INVALID_TICKET_URL"),
      EventRules.https(in.image(), "INVALID_IMAGE_URL"), note
    };
  }

  @Transactional
  public StaffEvent create(EventInput in, String actor) {
    Object[] v = cleaned(in);
    UUID id = UUID.randomUUID();
    String base =
        in.slug() != null && in.slug().matches("[a-z0-9]+(-[a-z0-9]+)*") && in.slug().length() <= 120
            ? in.slug()
            : EventRules.slugify(
                (String) v[0], in.startsAt().atZone(EventRules.BAKU).toLocalDate());
    String slug = base;
    for (int i = 2;
        db.queryForObject("SELECT count(*) FROM events WHERE slug=?", Integer.class, slug) > 0;
        i++) {
      if (i > 30) throw DomainException.conflict("SLUG_UNAVAILABLE");
      slug = base + "-" + i;
    }
    Object[] args = new Object[v.length + 4];
    args[0] = id;
    args[1] = slug;
    System.arraycopy(v, 0, args, 2, v.length);
    args[v.length + 2] = actor;
    args[v.length + 3] = actor;
    db.update(
        "INSERT INTO events(id,slug,title_en,title_ru,title_az,description_en,description_ru,"
            + "description_az,category,city_slug,venue_name,address,latitude,longitude,starts_at,"
            + "ends_at,time_known,is_free,price_from_minor,currency,ticket_url,image,source_note,"
            + "created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        args);
    audit(id, actor, "CREATED", "");
    return get(id);
  }

  @Transactional
  public StaffEvent update(UUID id, EventInput in, String actor) {
    var row = db.queryForList("SELECT version, source FROM events WHERE id=? FOR UPDATE", id);
    if (row.isEmpty()) throw DomainException.missing();
    if (in.version() == null || in.version() != ((Number) row.getFirst().get("version")).intValue())
      throw DomainException.conflict("STALE_EVENT");
    Object[] v = cleaned(in);
    Object[] args = new Object[v.length + 2];
    System.arraycopy(v, 0, args, 0, v.length);
    args[v.length] = actor;
    args[v.length + 1] = id;
    db.update(
        "UPDATE events SET title_en=?,title_ru=?,title_az=?,description_en=?,description_ru=?,"
            + "description_az=?,category=?,city_slug=?,venue_name=?,address=?,latitude=?,longitude=?,"
            + "starts_at=?,ends_at=?,time_known=?,is_free=?,price_from_minor=?,currency=?,ticket_url=?,"
            + "image=?,source_note=?,updated_by=?,version=version+1,updated_at=now(),"
            + "admin_locked=(admin_locked OR source='EXTERNAL') WHERE id=?",
        args);
    audit(id, actor, "UPDATED", "");
    return get(id);
  }

  @Transactional
  public StaffEvent publish(UUID id, String actor) {
    var row = db.queryForList("SELECT * FROM events WHERE id=? FOR UPDATE", id);
    if (row.isEmpty()) throw DomainException.missing();
    var e = row.getFirst();
    String status = (String) e.get("status");
    if (status.equals("PUBLISHED")) throw DomainException.conflict("ALREADY_PUBLISHED");
    if (status.equals("CANCELLED")) throw DomainException.conflict("EVENT_CANCELLED");
    if (((String) e.get("description_en")).isBlank() || ((String) e.get("description_az")).isBlank())
      throw DomainException.bad("DESCRIPTION_REQUIRED");
    if (((Timestamp) e.get("ends_at")).toInstant().isBefore(Instant.now()))
      throw DomainException.conflict("EVENT_ALREADY_ENDED");
    db.update(
        "UPDATE events SET status='PUBLISHED',updated_by=?,version=version+1,updated_at=now() WHERE id=?",
        actor, id);
    audit(id, actor, "PUBLISHED", "");
    return get(id);
  }

  @Transactional
  public StaffEvent cancel(UUID id, String actor, String note) {
    var row = db.queryForList("SELECT status FROM events WHERE id=? FOR UPDATE", id);
    if (row.isEmpty()) throw DomainException.missing();
    String status = (String) row.getFirst().get("status");
    if (status.equals("CANCELLED")) throw DomainException.conflict("ALREADY_CANCELLED");
    if (status.equals("DRAFT")) throw DomainException.conflict("DRAFT_CANNOT_BE_CANCELLED");
    db.update(
        "UPDATE events SET status='CANCELLED',updated_by=?,version=version+1,updated_at=now() WHERE id=?",
        actor, id);
    audit(id, actor, "CANCELLED", Optional.ofNullable(EventRules.plain(note)).orElse(""));
    return get(id);
  }

  @Transactional
  public void delete(UUID id, String actor) {
    var row = db.queryForList("SELECT status FROM events WHERE id=? FOR UPDATE", id);
    if (row.isEmpty()) throw DomainException.missing();
    if (!row.getFirst().get("status").equals("DRAFT"))
      throw DomainException.conflict("ONLY_DRAFTS_CAN_BE_DELETED");
    db.update("DELETE FROM events WHERE id=?", id);
    audit(id, actor, "DELETED", "");
  }

  public List<Map<String, Object>> auditTrail(UUID id) {
    return db.queryForList(
        "SELECT actor, action, note, created_at FROM event_audit WHERE event_id=? ORDER BY created_at",
        id);
  }
}
