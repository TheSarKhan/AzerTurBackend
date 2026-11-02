package az.azertur.payment;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
class PaymentAmountsTest {
 @Test void preservesCentsExactly() {
  assertEquals(1015,PaymentAmounts.minor(new BigDecimal("10.15")));
  assertEquals(1000,PaymentAmounts.minor(new BigDecimal("10.000")));
  assertThrows(RuntimeException.class,()->PaymentAmounts.minor(new BigDecimal("10.001")));
  assertThrows(RuntimeException.class,()->PaymentAmounts.minor(new BigDecimal("-1")));
 }
 @Test void includesDepositInTotalCap() {
  PaymentAmounts.within(7000,3000,10000);
  assertThrows(RuntimeException.class,()->PaymentAmounts.within(7001,3000,10000));
  assertThrows(RuntimeException.class,()->PaymentAmounts.minor(new BigDecimal("1e30")));
 }
}
