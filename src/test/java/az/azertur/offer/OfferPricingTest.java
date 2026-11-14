package az.azertur.offer;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OfferPricingTest {
  private OfferService.Line line(BigDecimal unit, BigDecimal cost, BigDecimal margin) {
    return new OfferService.Line(
        "guide-2", "Guide", 2, 1, unit, "day", "", "", "NOT_CHECKED", "", "", 1, cost, margin, null);
  }

  @Test
  void customerPriceIsCostPlusMargin_defaultingToFifteenPercent() {
    var p = OfferService.priced(line(new BigDecimal("1"), new BigDecimal("100"), null));
    assertEquals(0, new BigDecimal("115").compareTo(p.unitPrice()));
    assertEquals(0, new BigDecimal("15").compareTo(p.marginPercent()));
    assertEquals(230, OfferService.amount(p));
  }

  @Test
  void marginIsPerLine() {
    var p = OfferService.priced(line(new BigDecimal("1"), new BigDecimal("40"), new BigDecimal("25")));
    assertEquals(0, new BigDecimal("50").compareTo(p.unitPrice()));
  }

  @Test
  void aLineWithoutACostKeepsItsPrice() {
    var l = line(new BigDecimal("77.5"), null, null);
    assertSame(l, OfferService.priced(l));
  }

  @Test
  void suffixedKeysKeepTheirKind() {
    assertEquals("guide", OfferService.baseKey("guide-2"));
    assertEquals("hotel", OfferService.baseKey("hotel"));
    assertEquals("photographer", OfferService.baseKey("photographer-12"));
  }

  @Test
  void aTypedCustomerPriceWinsAndTheMarginFollowsFromIt() {
    var typed =
        new OfferService.Line(
            "guide", "Guide", 1, 1, new BigDecimal("1"), "day", "", "", "CONFIRMED", "", "", 1,
            new BigDecimal("80"), new BigDecimal("15"), new BigDecimal("100"));
    var p = OfferService.priced(typed);
    assertEquals(0, new BigDecimal("100").compareTo(p.unitPrice()));
    assertEquals(0, new BigDecimal("25").compareTo(p.marginPercent()));
  }

  @Test
  void aTypedPriceBelowCostGivesANegativeMargin() {
    var typed =
        new OfferService.Line(
            "guide", "Guide", 1, 1, new BigDecimal("1"), "day", "", "", "CONFIRMED", "", "", 1,
            new BigDecimal("100"), null, new BigDecimal("90"));
    assertEquals(0, new BigDecimal("-10").compareTo(OfferService.priced(typed).marginPercent()));
  }
}
