package az.azertur.quote;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class QuoteCalculator {
  // Any destination may follow any other (the team arranges the transfers), so a route only has to
  // name known destinations.
  private static final Set<String> CITIES = Set.of("Baku", "Quba", "Khinalug", "Gabala", "Sheki");

  public QuoteResult calculate(QuoteInput q, int tourDays) {
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Baku"));
    if (q.date().isBefore(today.plusDays(3)) || q.date().isAfter(today.plusDays(365)))
      throw new IllegalArgumentException("DATE_NOT_AVAILABLE");
    int days = q.customDays() == null ? tourDays : q.customDays();
    if (q.customDays() != null) {
      if (q.cities() == null || q.cities().size() != days)
        throw new IllegalArgumentException("INVALID_ROUTE");
      for (String city : q.cities())
        if (!CITIES.contains(city)) throw new IllegalArgumentException("INVALID_ROUTE");
    }
    double fx =
        switch (q.currency()) {
          case "USD" -> 1.1;
          case "AZN" -> 1.87;
          default -> 1;
        };
    List<QuoteResult.Line> lines = new ArrayList<>();
    if (!Boolean.FALSE.equals(q.driver()))
      add(lines, "transport", days * (q.pax() <= 3 ? 90 : q.pax() <= 7 ? 135 : 180), fx);
    int hotel =
        switch (q.hotel()) {
          case "standard" -> 65;
          case "premium" -> 190;
          default -> 110;
        };
    if (!Boolean.FALSE.equals(q.stay()) && days > 1)
      add(lines, "hotel", (days - 1) * ((q.pax() + 1) / 2) * hotel, fx);
    if (q.guide()) add(lines, "guide", days * 65, fx);
    if (q.transfer()) add(lines, "transfer", 35, fx);
    if (q.activity()) add(lines, "activity", 20 * q.pax(), fx);
    if (lines.isEmpty()) throw new IllegalArgumentException("NO_SERVICES");
    int total = lines.stream().mapToInt(QuoteResult.Line::amount).sum(),
        deposit = (int) Math.round(total * .3);
    return new QuoteResult(
        UUID.randomUUID(),
        "PRICED",
        q.currency(),
        total,
        (int) Math.round((double) total / q.pax()),
        deposit,
        total - deposit,
        q.date().minusDays(7),
        Instant.now().plus(Duration.ofHours(72)),
        List.copyOf(lines),
        q,
        true);
  }

  private void add(List<QuoteResult.Line> lines, String key, int euros, double fx) {
    lines.add(new QuoteResult.Line(key, (int) Math.round(euros * fx)));
  }
}
