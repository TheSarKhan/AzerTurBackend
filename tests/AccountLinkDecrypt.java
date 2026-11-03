import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
// Synthetic loopback test fixture only; key and encrypted fixture are supplied via stdin.
class AccountLinkDecrypt {
 public static void main(String[] args)throws Exception{
  var scan=new Scanner(System.in);var key=Base64.getDecoder().decode(scan.nextLine());var id=scan.nextLine();var nonce=HexFormat.of().parseHex(scan.nextLine());var content=HexFormat.of().parseHex(scan.nextLine());
  var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(id.getBytes(StandardCharsets.US_ASCII));System.out.print(new String(cipher.doFinal(content),StandardCharsets.UTF_8));
 }
}
