package az.azertur.customer;

import az.azertur.shared.DomainException;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** Verifies a Google ID token (signature via Google's public keys, issuer, audience, expiry). */
@Component
public class GoogleIdentityVerifier {
  public record Identity(String subject, String email, String name) {}

  public record Config(
      boolean enabled, String webClientId, String iosClientId, String androidClientId) {}

  private static final Set<String> ISSUERS =
      Set.of("https://accounts.google.com", "accounts.google.com");
  private final Config config;
  private final Set<String> audiences = new HashSet<>();
  private final JwtDecoder decoder;

  @Autowired
  public GoogleIdentityVerifier(
      @Value("${app.security.google-web-client-id:}") String web,
      @Value("${app.security.google-ios-client-id:}") String ios,
      @Value("${app.security.google-android-client-id:}") String android,
      @Value("${app.security.google-jwk-set-uri}") String jwkSetUri) {
    this(web, ios, android, decoder(jwkSetUri));
  }

  GoogleIdentityVerifier(String web, String ios, String android, JwtDecoder decoder) {
    for (String id : List.of(web, ios, android)) if (!id.isBlank()) audiences.add(id.trim());
    this.config =
        new Config(!audiences.isEmpty(), web.trim(), ios.trim(), android.trim());
    this.decoder = decoder;
  }

  private static JwtDecoder decoder(String jwkSetUri) {
    var requests = new SimpleClientHttpRequestFactory();
    requests.setConnectTimeout(3000);
    requests.setReadTimeout(3000);
    var decoder =
        NimbusJwtDecoder.withJwkSetUri(jwkSetUri).restOperations(new RestTemplate(requests)).build();
    decoder.setJwtValidator(JwtValidators.createDefault());
    return decoder;
  }

  public Config config() {
    return config;
  }

  public Identity verify(String credential) {
    if (!config.enabled())
      throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_SIGN_IN_DISABLED");
    Jwt jwt;
    try {
      jwt = decoder.decode(credential);
    } catch (BadJwtException e) {
      throw new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN");
    } catch (JwtException e) {
      throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_UNAVAILABLE");
    }
    return identity(jwt);
  }

  Identity identity(Jwt jwt) {
    List<String> audience = jwt.getAudience();
    if (!ISSUERS.contains(jwt.getClaimAsString("iss"))
        || audience == null
        || audience.stream().noneMatch(audiences::contains)
        || jwt.getSubject() == null
        || jwt.getSubject().isBlank())
      throw new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN");
    String email = jwt.getClaimAsString("email");
    Object verified = jwt.getClaim("email_verified");
    if (email == null
        || email.isBlank()
        || !(Boolean.TRUE.equals(verified) || "true".equals(verified)))
      throw new DomainException(HttpStatus.UNAUTHORIZED, "GOOGLE_EMAIL_NOT_VERIFIED");
    String name = jwt.getClaimAsString("name");
    if (name == null || name.trim().length() < 2) name = email.substring(0, email.indexOf('@'));
    name = name.trim();
    return new Identity(jwt.getSubject(), email.trim(), name.substring(0, Math.min(100, name.length())));
  }
}
