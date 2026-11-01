package az.azertur.staff;

import az.azertur.shared.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StaffAuthService {
  public static final Set<String> ROLES = Set.of("ADMIN", "OPERATOR");
  private static final int MIN_PASSWORD = 12;

  public record Staff(UUID id, String email, String name, String role) implements Principal {
    @Override
    public String getName() {
      return email;
    }
  }

  public record Account(
      UUID id,
      String email,
      String name,
      String role,
      boolean active,
      Instant createdAt,
      Instant lastLoginAt) {}

  public record Session(String token, Instant expires, Staff staff) {}

  private final JdbcTemplate db;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
  private final String dummyHash = encoder.encode("non-user timing comparison");
  private final SecureRandom random = new SecureRandom();

  public StaffAuthService(JdbcTemplate db) {
    this.db = db;
  }

  private static String normalize(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private static void checkPassword(String password) {
    if (password.length() < MIN_PASSWORD) throw DomainException.bad("PASSWORD_TOO_SHORT");
    if (password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw DomainException.bad("PASSWORD_TOO_LONG");
  }

  private String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Transactional
  public Session login(String email, String password) {
    var rows =
        db.query(
            "SELECT id,email,name,role,password_hash FROM staff_users WHERE email=? AND active",
            (r, n) ->
                Map.entry(
                    new Staff(
                        r.getObject("id", UUID.class),
                        r.getString("email"),
                        r.getString("name"),
                        r.getString("role")),
                    r.getString("password_hash")),
            normalize(email));
    String encoded = rows.isEmpty() ? dummyHash : rows.getFirst().getValue();
    if (password.getBytes(StandardCharsets.UTF_8).length > 72
        || !encoder.matches(password, encoded)
        || rows.isEmpty())
      throw new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
    Staff staff = rows.getFirst().getKey();
    db.update("UPDATE staff_users SET last_login_at=now() WHERE id=?", staff.id());
    db.update("DELETE FROM staff_sessions WHERE expires_at<now()");
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant expires = Instant.now().plus(Duration.ofHours(12));
    db.update(
        "INSERT INTO staff_sessions(token_hash,staff_id,expires_at) VALUES(?,?,?)",
        hash(token),
        staff.id(),
        java.sql.Timestamp.from(expires));
    return new Session(token, expires, staff);
  }

  public Optional<Staff> authenticate(String token) {
    if (token == null || token.length() > 128) return Optional.empty();
    return db
        .query(
            "SELECT u.id,u.email,u.name,u.role FROM staff_users u JOIN staff_sessions s ON"
                + " s.staff_id=u.id WHERE s.token_hash=? AND s.expires_at>now() AND u.active",
            (r, n) ->
                new Staff(
                    r.getObject("id", UUID.class),
                    r.getString("email"),
                    r.getString("name"),
                    r.getString("role")),
            hash(token))
        .stream()
        .findFirst();
  }

  public void logout(String token) {
    db.update("DELETE FROM staff_sessions WHERE token_hash=?", hash(token));
  }

  public List<Account> list() {
    return db.query(
        "SELECT id,email,name,role,active,created_at,last_login_at FROM staff_users ORDER BY"
            + " created_at",
        (r, n) ->
            new Account(
                r.getObject("id", UUID.class),
                r.getString("email"),
                r.getString("name"),
                r.getString("role"),
                r.getBoolean("active"),
                r.getTimestamp("created_at").toInstant(),
                r.getTimestamp("last_login_at") == null
                    ? null
                    : r.getTimestamp("last_login_at").toInstant()));
  }

  @Transactional
  public Account create(String email, String name, String role, String password) {
    if (!ROLES.contains(role)) throw DomainException.bad("INVALID_ROLE");
    checkPassword(password);
    UUID id = UUID.randomUUID();
    try {
      db.update(
          "INSERT INTO staff_users(id,email,name,password_hash,role) VALUES(?,?,?,?,?)",
          id,
          normalize(email),
          name.trim(),
          encoder.encode(password),
          role);
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw DomainException.conflict("ACCOUNT_UNAVAILABLE");
    }
    return list().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
  }

  /** Keeps at least one active administrator and stops an admin from locking themself out. */
  @Transactional
  public void setActive(UUID id, boolean active, UUID actor) {
    lockAdmins();
    if (!active && id.equals(actor)) throw DomainException.conflict("CANNOT_DEACTIVATE_SELF");
    if (db.update("UPDATE staff_users SET active=? WHERE id=?", active, id) == 0)
      throw new DomainException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND");
    if (!active) db.update("DELETE FROM staff_sessions WHERE staff_id=?", id);
    requireActiveAdmin();
  }

  @Transactional
  public void changeRole(UUID id, String role, UUID actor) {
    if (!ROLES.contains(role)) throw DomainException.bad("INVALID_ROLE");
    lockAdmins();
    if (id.equals(actor) && !role.equals("ADMIN"))
      throw DomainException.conflict("CANNOT_DEMOTE_SELF");
    if (db.update("UPDATE staff_users SET role=? WHERE id=?", role, id) == 0)
      throw new DomainException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND");
    db.update("DELETE FROM staff_sessions WHERE staff_id=?", id);
    requireActiveAdmin();
  }

  @Transactional
  public void resetPassword(UUID id, String password) {
    checkPassword(password);
    if (db.update(
            "UPDATE staff_users SET password_hash=? WHERE id=?", encoder.encode(password), id)
        == 0) throw new DomainException(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND");
    db.update("DELETE FROM staff_sessions WHERE staff_id=?", id);
  }

  /** Serialises administrator changes so two concurrent requests cannot remove the last admin. */
  private void lockAdmins() {
    db.query("SELECT id FROM staff_users WHERE role='ADMIN' FOR UPDATE", (r, n) -> 1);
  }

  private void requireActiveAdmin() {
    Integer admins =
        db.queryForObject(
            "SELECT count(*) FROM staff_users WHERE role='ADMIN' AND active", Integer.class);
    if (admins == null || admins < 1) throw DomainException.conflict("LAST_ADMIN");
  }

  /** Creates the first administrator from configuration when no staff account exists yet. */
  @Transactional
  public boolean bootstrap(String email, String name, String password) {
    Integer existing = db.queryForObject("SELECT count(*) FROM staff_users", Integer.class);
    if (existing != null && existing > 0) return false;
    create(email, name, "ADMIN", password);
    return true;
  }
}
