package az.azertur.quote;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteCalculatorTest {
  private QuoteInput input(String currency, Integer days, List<String> cities) {
    return new QuoteInput(
        "quba-khinalug",
        LocalDate.now(ZoneId.of("Asia/Baku")).plusDays(30),
        3,
        "comfort",
        true,
        true,
        true,
        "en",
        false,
        false,
        currency,
        days,
        cities,
        null,
        null,
        null,
        null);
  }

  @Test
  void preservesPricingAcrossCurrencies() {
    var calculator = new QuoteCalculator();
    String[] currencies = {"EUR", "USD", "AZN"};
    int[] expected = {530, 583, 991};
    for (int i = 0; i < 3; i++) {
      var q = calculator.calculate(input(currencies[i], null, null), 2);
      assertEquals(expected[i], q.total());
      assertEquals(q.total(), q.deposit() + q.balance());
      assertEquals(q.input().date().minusDays(7), q.due());
    }
  }

  @Test
  void rejectsUnknownDestinationsAndWrongDayCounts() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new QuoteCalculator().calculate(input("EUR", 2, List.of("Baku", "Atlantis")), 2));
    assertThrows(
        IllegalArgumentException.class,
        () -> new QuoteCalculator().calculate(input("EUR", 3, List.of("Baku", "Quba")), 2));
  }

  @Test
  void acceptsAnyOrderOfKnownDestinations() {
    for (var route :
        List.of(
            List.of("Baku", "Khinalug"),
            List.of("Sheki", "Quba"),
            List.of("Khinalug", "Gabala", "Quba"),
            List.of("Gabala", "Sheki", "Gabala", "Sheki")))
      assertEquals(
          route.size(),
          new QuoteCalculator().calculate(input("EUR", route.size(), route), 2).input().cities().size());
  }

  @Test
  void acceptsConnectedRoutes() {
    assertEquals(
        905,
        new QuoteCalculator()
            .calculate(input("EUR", 3, List.of("Baku", "Quba", "Khinalug")), 2)
            .total());
  }

  @Test
  void startAndEndDefaultToBakuAndDoNotChangeThePrice() {
    var calculator = new QuoteCalculator();
    var plain = input("EUR", 2, List.of("Quba", "Khinalug"));
    var chosen =
        new QuoteInput(
            plain.slug(), plain.date(), plain.pax(), plain.hotel(), plain.driver(), plain.stay(),
            plain.guide(), plain.guideLanguage(), plain.transfer(), plain.activity(),
            plain.currency(), plain.customDays(), plain.cities(), null, "Gabala", "Sheki", null);
    var a = calculator.calculate(plain, 2);
    var b = calculator.calculate(chosen, 2);
    assertEquals("Baku", a.input().startCity());
    assertEquals("Baku", a.input().endCity());
    assertEquals("Gabala", b.input().startCity());
    assertEquals("Sheki", b.input().endCity());
    assertEquals(a.total(), b.total());
  }

  private QuoteInput withPlan(List<QuoteInput.DayPlan> plan) {
    var base = input("EUR", null, null);
    return new QuoteInput(
        base.slug(), base.date(), 2, "comfort", null, null, false, "en", false, false, "EUR",
        null, null, null, null, null, plan);
  }

  private QuoteInput.DayPlan day(QuoteInput.Place... places) {
    return new QuoteInput.DayPlan(List.of(places));
  }

  private QuoteInput.Place place(String city, String... services) {
    return new QuoteInput.Place(city, List.of(services));
  }

  @Test
  void aDayPlanFixesTheDaysRouteAndFlags() {
    var q =
        withPlan(
                List.of(
                    day(place("Quba", "transport", "guide"), place("Khinalug", "activity", "meals")),
                    day(place("Sheki", "hotel", "transfer", "photographer"))))
            .withEnds();
    assertEquals(2, q.customDays());
    assertEquals(List.of("Quba", "Sheki"), q.cities());
    assertTrue(q.guide() && q.transfer() && q.activity());
    assertEquals(List.of("meals", "photographer"), q.extras());
  }

  @Test
  void aCarAndAGuideCountOncePerDayButATransferCountsPerPlace() {
    var calculator = new QuoteCalculator();
    var oneDayTwoPlaces =
        withPlan(List.of(day(place("Quba", "transport", "guide", "transfer"), place("Khinalug", "transport", "guide", "transfer"))));
    var r = calculator.calculate(oneDayTwoPlaces, 1);
    var amounts = new java.util.HashMap<String, Integer>();
    r.lines().forEach(l -> amounts.put(l.label(), l.amount()));
    assertEquals(90, amounts.get("transport"));
    assertEquals(65, amounts.get("guide"));
    assertEquals(70, amounts.get("transfer"));
  }

  @Test
  void aPlanWithoutAMainServiceIsRejected() {
    var calculator = new QuoteCalculator();
    var onlyExtras = withPlan(List.of(day(place("Quba", "meals"))));
    assertThrows(IllegalArgumentException.class, () -> calculator.calculate(onlyExtras, 1));
  }

  @Test
  void aTransferToTheNextRegionIsPricedPerLegInTheTransferLine() {
    var calculator = new QuoteCalculator();
    var plan =
        withPlan(
            List.of(
                day(place("Quba", "transport", "intercity")),
                day(place("Khinalug", "intercity", "transfer"))));
    var q = plan.withEnds();
    assertTrue(q.transfer());
    var r = calculator.calculate(plan, 2);
    var transfer = r.lines().stream().filter(l -> l.label().equals("transfer")).findFirst().orElseThrow();
    assertEquals(35 + 2 * 60, transfer.amount());
  }
}
