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
        cities);
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
}
