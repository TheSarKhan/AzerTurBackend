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
  public record Customer(UUID id, String email, String name, String locale) {}

  public record Session(String token, Instant expires, Customer customer) {}

  private final JdbcTemplate db;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
  private final String dummyHash = encoder.encode("non-user timing comparison");
  private final SecureRandom random = new SecureRandom();

  public CustomerAuthService(JdbcTemplate db) {
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
  public Session register(String email, String password, String name, String locale) {
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
    return createSession(new Customer(id, normalized, name.trim(), locale));
  }

  @Transactional
  public Session login(String email, String password) {
    var rows =
        db.query(
            "SELECT * FROM customers WHERE email=?",
            (r, n) ->
                Map.entry(
                    new Customer(
                        r.getObject("id", UUID.class),
                        r.getString("email"),
                        r.getString("name"),
                        r.getString("locale")),
                    r.getString("password_hash")),
            email.trim().toLowerCase(Locale.ROOT));
    String encoded = rows.isEmpty() ? dummyHash : rows.getFirst().getValue();
    if (password.getBytes(StandardCharsets.UTF_8).length > 72
        || !encoder.matches(password, encoded)
        || rows.isEmpty())
      throw new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
    return createSession(rows.getFirst().getKey());
  }

  private Session createSession(Customer customer) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant expires = Instant.now().plus(Duration.ofDays(7));
    db.update(
        "INSERT INTO customer_sessions(token_hash,customer_id,expires_at) VALUES(?,?,?)",
        hash(token),
        customer.id(),
        java.sql.Timestamp.from(expires));
    return new Session(token, expires, customer);
  }

  public Optional<Customer> authenticate(String token) {
    if (token == null || token.length() > 128) return Optional.empty();
    return db
        .query(
            "SELECT c.* FROM customers c JOIN customer_sessions s ON s.customer_id=c.id WHERE"
                + " s.token_hash=? AND s.expires_at>now()",
            (r, n) ->
                new Customer(
                    r.getObject("id", UUID.class),
                    r.getString("email"),
                    r.getString("name"),
                    r.getString("locale")),
            hash(token))
        .stream()
        .findFirst();
  }

  public void logout(String token) {
    db.update("DELETE FROM customer_sessions WHERE token_hash=?", hash(token));
  }
}
