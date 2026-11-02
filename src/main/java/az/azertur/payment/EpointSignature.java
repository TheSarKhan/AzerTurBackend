package az.azertur.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** Epoint's documented base64(sha1(privateKey + data + privateKey, raw=true)). */
public final class EpointSignature {
  private EpointSignature() {}
  public static String sign(String data,String privateKey) {
    if (privateKey==null || privateKey.isBlank()) throw new IllegalStateException("EPOINT_NOT_CONFIGURED");
    try {
      byte[] digest=MessageDigest.getInstance("SHA-1").digest((privateKey+data+privateKey).getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(digest);
    } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
  public static boolean verify(String data,String signature,String privateKey) {
    if (data==null || data.length()>65536 || signature==null || signature.length()!=28) return false;
    return MessageDigest.isEqual(sign(data,privateKey).getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII));
  }
}
