package az.azertur.customer;

import static org.junit.jupiter.api.Assertions.*;

import az.azertur.shared.DomainException;
import org.junit.jupiter.api.Test;

class CustomerPhoneTest {
  @Test
  void keepsDigitsAndALeadingPlus() {
    assertEquals("+994501234567", CustomerAuthService.normalizePhone("+994 (50) 123-45-67"));
    assertEquals("0501234567", CustomerAuthService.normalizePhone(" 050 123 45 67 "));
  }

  @Test
  void blankMeansNoNumber() {
    assertNull(CustomerAuthService.normalizePhone(null));
    assertNull(CustomerAuthService.normalizePhone("  "));
  }

  @Test
  void rejectsNumbersThatAreTooShortOrTooLong() {
    assertThrows(DomainException.class, () -> CustomerAuthService.normalizePhone("12345"));
    assertThrows(DomainException.class, () -> CustomerAuthService.normalizePhone("+1234567890123456"));
    assertThrows(DomainException.class, () -> CustomerAuthService.normalizePhone("abc"));
  }
}
