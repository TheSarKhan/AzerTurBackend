package az.azertur.customer;

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
public class CustomerAuthService {
  public record Customer(UUID id, String email, String name, String locale, boolean emailVerified) {}

  public record Session(String token, Instant expires, Customer customer) {}

  private final JdbcTemplate db;
  private final AccountSecurityService security;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
  private final String dummyHash = encoder.encode("non-user timing comparison");
  private final SecureRandom random = new SecureRandom();

  public CustomerAuthService(JdbcTemplate db,AccountSecurityService security) {
    this.security=security;
    this.db = db;
  }

  public String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Transactional
  public Session register(String email, String password, String name, String locale, String userAgent) {
    if (password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw DomainException.bad("PASSWORD_TOO_LONG");
    UUID id = UUID.randomUUID();
    String normalized = email.trim().toLowerCase(Locale.ROOT);
    try {
      db.update(
          "INSERT INTO customers(id,email,name,password_hash,locale) VALUES(?,?,?,?,?)",
          id,
          normalized,
          name.trim(),
          encoder.encode(password),
          locale);
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw DomainException.conflict("ACCOUNT_UNAVAILABLE");
    }
    security.verification(id);
    return createSession(new Customer(id, normalized, name.trim(), locale,false),userAgent);
  }

  @Transactional
  public Session login(String email, String password,String userAgent) {
    var rows =
        db.query(
            "SELECT * FROM customers WHERE email=? FOR UPDATE",
            (r, n) ->
                Map.entry(
                    new Customer(
                        r.getObject("id", UUID.class),
                        r.getString("email"),
                        r.getString("name"),
                        r.getString("locale"),r.getTimestamp("email_verified_at")!=null),
                    r.getString("password_hash")),
            email.trim().toLowerCase(Locale.ROOT));
    String encoded = rows.isEmpty() ? dummyHash : rows.getFirst().getValue();
    if (password.getBytes(StandardCharsets.UTF_8).length > 72
        || !encoder.matches(password, encoded)
        || rows.isEmpty())
      throw new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
    return createSession(rows.getFirst().getKey(),userAgent);
  }

  private static final org.springframework.jdbc.core.RowMapper<Customer> CUSTOMER =
      (r, n) ->
          new Customer(
              r.getObject("id", UUID.class),
              r.getString("email"),
              r.getString("name"),
              r.getString("locale"),
              r.getTimestamp("email_verified_at") != null);

  /**
   * Signs in with a verified Google identity: a returning identity, an existing account with the
   * same email (linked) or a new account. Google has verified the address, so it counts as verified.
   */
  @Transactional
  public Session google(GoogleIdentityVerifier.Identity google, String locale, String userAgent) {
    String email = google.email().toLowerCase(Locale.ROOT);
    var linked =
        db.query(
            "SELECT c.* FROM customer_identities i JOIN customers c ON c.id=i.customer_id WHERE"
                + " i.provider='GOOGLE' AND i.subject=? FOR UPDATE OF c",
            CUSTOMER,
            google.subject());
    if (!linked.isEmpty()) return createSession(linked.getFirst(), userAgent);
    var existing = db.query("SELECT * FROM customers WHERE email=? FOR UPDATE", CUSTOMER, email);
    Customer customer;
    String action;
    if (existing.isEmpty()) {
      UUID id = UUID.randomUUID();
      db.update(
          "INSERT INTO customers(id,email,name,password_hash,locale,email_verified_at)"
              + " VALUES(?,?,?,?,?,now())",
          id,
          email,
          google.name(),
          encoder.encode(secret()),
          locale);
      customer = new Customer(id, email, google.name(), locale, true);
      action = "GOOGLE_SIGNUP";
    } else {
      customer = existing.getFirst();
      if (!customer.emailVerified()) {
        // Nobody has proven control of this address yet. Whoever registered it with a password
        // (possibly not its owner) must not keep access once the real owner signs in with Google.
        db.update(
            "UPDATE customers SET password_hash=?, email_verified_at=now() WHERE id=?",
            encoder.encode(secret()),
            customer.id());
        db.update("DELETE FROM customer_sessions WHERE customer_id=?", customer.id());
        db.update(
            "UPDATE account_links SET consumed_at=now() WHERE customer_id=? AND consumed_at IS NULL",
            customer.id());
        customer = new Customer(customer.id(), email, customer.name(), customer.locale(), true);
      }
      action = "GOOGLE_LINKED";
    }
    db.update(
        "INSERT INTO customer_identities(provider,subject,customer_id,email) VALUES('GOOGLE',?,?,?)",
        google.subject(),
        customer.id(),
        email);
    db.update(
        "INSERT INTO customer_security_audit(id,customer_id,action) VALUES(?,?,?)",
        UUID.randomUUID(),
        customer.id(),
        action);
    return createSession(customer, userAgent);
  }

  private String secret() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private Session createSession(Customer customer,String userAgent) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant expires = Instant.now().plus(Duration.ofDays(7));
    db.update(
        "INSERT INTO customer_sessions(token_hash,customer_id,expires_at,device) VALUES(?,?,?,?)",
        hash(token),
        customer.id(),
        java.sql.Timestamp.from(expires),device(userAgent));
    return new Session(token, expires, customer);
  }

  public Optional<Customer> authenticate(String token) {
    if (token == null || token.length() > 128) return Optional.empty();
    db.update("UPDATE customer_sessions SET last_seen_at=now() WHERE token_hash=? AND expires_at>now() AND last_seen_at<now()-interval '5 minutes'",hash(token));
    return db
        .query(
            "SELECT c.* FROM customers c JOIN customer_sessions s ON s.customer_id=c.id WHERE"
                + " s.token_hash=? AND s.expires_at>now()",
            (r, n) ->
                new Customer(
                    r.getObject("id", UUID.class),
                    r.getString("email"),
                    r.getString("name"),
                    r.getString("locale"),r.getTimestamp("email_verified_at")!=null),
            hash(token))
        .stream()
        .findFirst();
  }

  private String device(String value) {
    String ua=value==null?"":value.substring(0,Math.min(512,value.length()));
    String browser=ua.contains("Firefox")?"Firefox":ua.contains("Edg/")?"Edge":ua.contains("Chrome")?"Chrome":ua.contains("Safari")?"Safari":"Browser";
    String system=ua.contains("Android")?"Android":ua.contains("iPhone")?"iPhone":ua.contains("iPad")?"iPad":ua.contains("Macintosh")?"Mac":ua.contains("Windows")?"Windows":ua.contains("Linux")?"Linux":"Other device";
    return system.equals("Other device")?system:browser+" / "+system;
  }
  public void logout(String token) {
    db.update("DELETE FROM customer_sessions WHERE token_hash=?", hash(token));
  }
}
