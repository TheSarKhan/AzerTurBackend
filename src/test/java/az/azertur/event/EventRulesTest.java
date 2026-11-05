package az.azertur.event;

import static org.junit.jupiter.api.Assertions.*;

import az.azertur.shared.DomainException;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EventRulesTest {
  @Test
  void markupIsRefusedButPlainTextWithSymbolsPasses() {
    assertThrows(DomainException.class, () -> EventRules.plain("<script>alert(1)</script>"));
    assertThrows(DomainException.class, () -> EventRules.plain("Hello <b>world</b>"));
    assertEquals("Tickets from 20 AZN > free for kids < 6", EventRules.plain("  Tickets from 20 AZN > free for kids < 6 "));
    assertNull(EventRules.plain(null));
  }

  @Test
  void slugsAreAsciiAndDated() {
    assertEquals("baku-city-circuit-20270924", EventRules.slugify("Baku City Circuit!", LocalDate.of(2027, 9, 24)));
    assertEquals("sebe-qebele-20261107", EventRules.slugify("Şəbə Qəbələ", LocalDate.of(2026, 11, 7)));
    assertEquals("event-20261107", EventRules.slugify("???", LocalDate.of(2026, 11, 7)));
    assertTrue(EventRules.slugify("x".repeat(300), LocalDate.of(2026, 11, 7)).length() <= 100);
  }

  @Test
  void datesAreBakuDaysOrInstants() {
    // 7 November in Baku (UTC+4) starts at 20:00 UTC the day before.
    assertEquals(Instant.parse("2026-11-06T20:00:00Z"), EventRules.parseBoundary("2026-11-07", false));
    assertEquals(Instant.parse("2026-11-07T20:00:00Z"), EventRules.parseBoundary("2026-11-07", true));
    assertEquals(Instant.parse("2026-11-07T10:15:00Z"), EventRules.parseBoundary("2026-11-07T10:15:00Z", false));
    assertNull(EventRules.parseBoundary(" ", false));
    assertThrows(DomainException.class, () -> EventRules.parseBoundary("tomorrow", false));
    assertThrows(DomainException.class, () -> EventRules.parseBoundary("2026-13-45", false));
  }

  @Test
  void locationMustBeCompleteAndInRange() {
    assertDoesNotThrow(() -> EventRules.checkLocation(null, null));
    assertDoesNotThrow(() -> EventRules.checkLocation(40.4, 49.8));
    assertDoesNotThrow(() -> EventRules.checkLocation(-90.0, 180.0));
    for (Double[] bad : new Double[][] {{40.4, null}, {null, 49.8}, {91.0, 0.0}, {0.0, 181.0}, {Double.NaN, 0.0}})
      assertThrows(DomainException.class, () -> EventRules.checkLocation(bad[0], bad[1]));
  }

  @Test
  void linksMustBeHttps() {
    assertEquals("https://iticket.az/en/events/x", EventRules.https(" https://iticket.az/en/events/x ", "E"));
    assertNull(EventRules.https("", "E"));
    for (String bad : new String[] {"http://example.com", "javascript:alert(1)", "//example.com", "https://a b.com", "ftp://x"})
      assertThrows(DomainException.class, () -> EventRules.https(bad, "E"));
  }
}
