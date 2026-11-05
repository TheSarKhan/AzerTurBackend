package az.azertur.customer;

import static org.junit.jupiter.api.Assertions.*;

import az.azertur.shared.DomainException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;

class GoogleIdentityVerifierTest {
  private static final String WEB = "web-client.apps.googleusercontent.com";
  private static final String IOS = "ios-client.apps.googleusercontent.com";

  private static Jwt token(Map<String, Object> overrides) {
    var claims = new java.util.HashMap<String, Object>(Map.of(
        "iss", "https://accounts.google.com",
        "aud", java.util.List.of(WEB),
        "sub", "1234567890",
        "email", "Traveller@Example.com",
        "email_verified", true,
        "name", "Test Traveller"));
    claims.putAll(overrides);
    return Jwt.withTokenValue("t").header("alg", "RS256").claims(c -> c.putAll(claims))
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
  }

  private static GoogleIdentityVerifier verifier() {
    return new GoogleIdentityVerifier(WEB, IOS, "", t -> token(Map.of()));
  }

  @Test
  void acceptsAValidTokenForAnyConfiguredClient() {
    var identity = verifier().identity(token(Map.of()));
    assertEquals("1234567890", identity.subject());
    assertEquals("Test Traveller", identity.name());
    assertEquals("Traveller@Example.com", identity.email());
    assertEquals("1234567890", verifier().identity(token(Map.of("aud", java.util.List.of(IOS)))).subject());
    assertEquals("1234567890", verifier().identity(token(Map.of("iss", "accounts.google.com"))).subject());
  }

  @Test
  void rejectsWrongIssuerAudienceOrSubject() {
    for (var bad : java.util.List.of(
        Map.<String, Object>of("iss", "https://evil.example"),
        Map.<String, Object>of("aud", java.util.List.of("someone-elses-client")),
        Map.<String, Object>of("sub", " ")))
      assertEquals("INVALID_GOOGLE_TOKEN",
          assertThrows(DomainException.class, () -> verifier().identity(token(bad))).getMessage());
  }

  @Test
  void rejectsUnverifiedOrMissingEmail() {
    for (var bad : java.util.List.of(
        Map.<String, Object>of("email_verified", false),
        Map.<String, Object>of("email_verified", "false"),
        Map.<String, Object>of("email", " ")))
      assertEquals("GOOGLE_EMAIL_NOT_VERIFIED",
          assertThrows(DomainException.class, () -> verifier().identity(token(bad))).getMessage());
    assertEquals("1234567890", verifier().identity(token(Map.of("email_verified", "true"))).subject());
  }

  @Test
  void fallsBackToTheEmailNameAndTruncatesLongNames() {
    assertEquals("Traveller", verifier().identity(token(Map.of("name", "x"))).name());
    assertEquals(100, verifier().identity(token(Map.of("name", "n".repeat(300)))).name().length());
  }

  @Test
  void isDisabledWithoutAClientId() {
    var disabled = new GoogleIdentityVerifier("", "", "", t -> token(Map.of()));
    assertFalse(disabled.config().enabled());
    assertEquals("GOOGLE_SIGN_IN_DISABLED",
        assertThrows(DomainException.class, () -> disabled.verify("anything")).getMessage());
    assertTrue(verifier().config().enabled());
  }

  @Test
  void separatesInvalidTokensFromGoogleBeingUnreachable() {
    var invalid = new GoogleIdentityVerifier(WEB, "", "", t -> { throw new BadJwtException("bad"); });
    assertEquals("INVALID_GOOGLE_TOKEN",
        assertThrows(DomainException.class, () -> invalid.verify("x")).getMessage());
    var down = new GoogleIdentityVerifier(WEB, "", "", t -> { throw new JwtException("jwks"); });
    assertEquals("GOOGLE_UNAVAILABLE",
        assertThrows(DomainException.class, () -> down.verify("x")).getMessage());
  }
}
