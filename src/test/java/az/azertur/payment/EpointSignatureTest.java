package az.azertur.payment;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class EpointSignatureTest {
 @Test void rejectsTamperingAndMissingConfiguration() {
  var signature=EpointSignature.sign("encoded-payload","test-secret");
  assertTrue(EpointSignature.verify("encoded-payload",signature,"test-secret"));
  assertFalse(EpointSignature.verify("changed",signature,"test-secret"));
  assertFalse(EpointSignature.verify("encoded-payload",signature,"other-secret"));
  assertFalse(EpointSignature.verify("encoded-payload",null,"test-secret"));
  assertThrows(IllegalStateException.class,()->EpointSignature.sign("payload",""));
 }
}
