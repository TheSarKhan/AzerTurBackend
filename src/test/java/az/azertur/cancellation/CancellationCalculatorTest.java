package az.azertur.cancellation;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
class CancellationCalculatorTest {
 final Instant early=Instant.parse("2027-01-01T00:00:00Z"),late=Instant.parse("2027-05-14T10:00:00Z");
 CancellationCalculator.Tier tier(Instant t,String percent,long fixed) { return new CancellationCalculator.Tier(t,new BigDecimal(percent),fixed,"PAID_AMOUNT"); }
 @Test void servicePoliciesDifferAndBoundaryIsInclusive() {
  var hotel=List.of(tier(early,"10",0),tier(late,"50",0));
  var flight=List.of(tier(early,"80",0),tier(late,"100",0));
  assertEquals(9000,CancellationCalculator.calculate(10000,10000,late.minusNanos(1),hotel).refundMinor());
  assertEquals(5000,CancellationCalculator.calculate(10000,10000,late,hotel).refundMinor());
  assertEquals(2000,CancellationCalculator.calculate(10000,10000,late.minusSeconds(1),flight).refundMinor());
  assertEquals(0,CancellationCalculator.calculate(10000,10000,late,flight).refundMinor());
 }
 @Test void penaltyCanBeBasedOnFullServicePrice() {
  assertEquals(0,CancellationCalculator.calculate(10000,100000,late,List.of(new CancellationCalculator.Tier(early,new BigDecimal("80"),0,"SERVICE_PRICE"))).refundMinor());
 }
 @Test void feeCannotExceedPaidAndNoUncoveredDefault() {
  assertEquals(0,CancellationCalculator.calculate(5,10000,late,List.of(tier(early,"50",100))).refundMinor());
  assertThrows(RuntimeException.class,()->CancellationCalculator.calculate(100,10000,early.minusSeconds(1),List.of(tier(early,"10",0))));
  assertThrows(RuntimeException.class,()->CancellationCalculator.calculate(100,10000,late,List.of(tier(early,"10",0),tier(early,"20",0))));
  assertEquals(0,CancellationCalculator.calculate(0,10000,late,List.of(tier(early,"100",500))).refundMinor());
 }
}
