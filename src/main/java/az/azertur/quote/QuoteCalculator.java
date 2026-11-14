package az.azertur.quote;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class QuoteCalculator {
  // Any destination may follow any other (the team arranges the transfers), so a route only has to
  // name known destinations.
  private static final Set<String> CITIES = Set.of("Baku", "Quba", "Khinalug", "Gabala", "Sheki");

  public QuoteResult calculate(QuoteInput requested, int tourDays) {
    QuoteInput q = requested.withEnds();
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
    // One minibus carries up to 12; larger groups need one per 12 travellers.
    int vehicles = Math.max(1, (q.pax() + 11) / 12);
    int carDay = q.pax() <= 3 ? 90 : q.pax() <= 7 ? 135 : 180 * vehicles;
    int hotel =
        switch (q.hotel()) {
          case "standard" -> 65;
          case "premium" -> 190;
          default -> 110;
        };
    if (q.plan() != null) {
      // Day by day: a car, a room and a guide count once per day however many places the day has;
      // a transfer or an experience counts once for every place that has one.
      int carDays = 0, nights = 0, guideDays = 0, transfers = 0, legs = 0, experiences = 0;
      for (var day : q.plan()) {
        boolean car = false, room = false, guide = false;
        for (var place : day.places()) {
          var s = place.services();
          car |= s.contains("transport");
          room |= s.contains("hotel");
          guide |= s.contains("guide");
          if (s.contains("transfer")) transfers++;
          if (s.contains("intercity")) legs++;
          if (s.contains("activity")) experiences++;
        }
        if (car) carDays++;
        if (room) nights++;
        if (guide) guideDays++;
      }
      if (carDays > 0) add(lines, "transport", carDays * carDay, fx);
      if (nights > 0) add(lines, "hotel", nights * ((q.pax() + 1) / 2) * hotel, fx);
      if (guideDays > 0) add(lines, "guide", guideDays * 65, fx);
      // An airport transfer and a transfer from one region to the next both go in the transfer line.
      if (transfers + legs > 0) add(lines, "transfer", (transfers * 35 + legs * 60) * vehicles, fx);
      if (experiences > 0) add(lines, "activity", experiences * 20 * q.pax(), fx);
    } else {
      if (!Boolean.FALSE.equals(q.driver())) add(lines, "transport", days * carDay, fx);
      if (!Boolean.FALSE.equals(q.stay()) && days > 1)
        add(lines, "hotel", (days - 1) * ((q.pax() + 1) / 2) * hotel, fx);
      if (q.guide()) add(lines, "guide", days * 65, fx);
      if (q.transfer()) add(lines, "transfer", 35 * vehicles, fx);
      if (q.activity()) add(lines, "activity", 20 * q.pax(), fx);
    }
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
        q);
  }

  private void add(List<QuoteResult.Line> lines, String key, int euros, double fx) {
    lines.add(new QuoteResult.Line(key, (int) Math.round(euros * fx)));
  }
}
