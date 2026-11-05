package az.azertur.event;

import az.azertur.shared.DomainException;
import java.text.Normalizer;
import java.time.*;
import java.util.Locale;
import java.util.regex.Pattern;

/** Input rules that do not need the database. */
final class EventRules {
  static final ZoneId BAKU = ZoneId.of("Asia/Baku");
  private static final Pattern HTML = Pattern.compile("<[a-zA-Z/!?]");

  private EventRules() {}

  /** Event text is shown as plain text everywhere, so markup is refused rather than stripped. */
  static String plain(String value) {
    if (value == null) return null;
    if (HTML.matcher(value).find()) throw DomainException.bad("HTML_NOT_ALLOWED");
    return value.strip();
  }

  static String slugify(String title, LocalDate date) {
    String ascii =
        Normalizer.normalize(
                // ı and ə have no Unicode decomposition, so map them by hand.
                title.toLowerCase(Locale.ROOT).replace('ı', 'i').replace('ə', 'e'), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+|-+$)", "");
    if (ascii.length() > 90) ascii = ascii.substring(0, 90).replaceAll("-+$", "");
    if (ascii.isEmpty()) ascii = "event";
    return ascii + "-" + date.toString().replace("-", "");
  }

  /** A plain date means Baku's start of that day; anything else must be an ISO-8601 instant. */
  static Instant parseBoundary(String value, boolean endOfDay) {
    if (value == null || value.isBlank()) return null;
    try {
      if (value.length() == 10) {
        LocalDate day = LocalDate.parse(value);
        return (endOfDay ? day.plusDays(1) : day).atStartOfDay(BAKU).toInstant();
      }
      return Instant.parse(value);
    } catch (java.time.format.DateTimeParseException e) {
      throw DomainException.bad("INVALID_DATE");
    }
  }

  static void checkLocation(Double lat, Double lng) {
    if ((lat == null) != (lng == null)) throw DomainException.bad("INVALID_LOCATION");
    if (lat != null && (!(lat >= -90 && lat <= 90) || !(lng >= -180 && lng <= 180)))
      throw DomainException.bad("INVALID_LOCATION");
  }

  static String https(String value, String code) {
    if (value == null || value.isBlank()) return null;
    String v = value.strip();
    if (!v.regionMatches(true, 0, "https://", 0, 8) || v.length() > 500 || v.chars().anyMatch(Character::isWhitespace))
      throw DomainException.bad(code);
    return v;
  }
}
