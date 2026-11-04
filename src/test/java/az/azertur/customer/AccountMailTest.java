package az.azertur.customer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
class AccountMailTest {
 static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);
 AccountSecurityService service(JdbcTemplate db,JavaMailSender mail,boolean enabled,String origin){return new AccountSecurityService(db,new ObjectMapper(),mail,mock(PlatformTransactionManager.class),KEY,origin,enabled,"qa@example.com",java.time.Instant.EPOCH);}
 @Test void disabledTransportNeverContactsSmtp(){var db=mock(JdbcTemplate.class);var mail=mock(JavaMailSender.class);service(db,mail,false,"https://example.com").deliver(UUID.randomUUID());verifyNoInteractions(db,mail);}
 @Test void rejectsUntrustedOrigins(){for(var origin:List.of("http://example.com","https://example.com/other","https://user@example.com","https://example.com?redirect=evil"))assertThrows(IllegalStateException.class,()->service(mock(JdbcTemplate.class),mock(JavaMailSender.class),false,origin));}
 @Test void successfulEncryptedMailIsSentOnceAndCipherWiped()throws Exception{exercise(false);}
 @Test void uncertainSmtpAcknowledgementIsNotRetriedAutomatically()throws Exception{exercise(true);}
 void exercise(boolean uncertain)throws Exception{
  var db=mock(JdbcTemplate.class);var mail=mock(JavaMailSender.class);UUID job=UUID.randomUUID();byte[] nonce=new byte[12];Arrays.fill(nonce,(byte)7);
  String url="https://example.com/az/account/verify#token=synthetic-test-link";
  var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(new byte[32],"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(job.toString().getBytes(StandardCharsets.US_ASCII));
  var encrypted=cipher.doFinal(("{\"locale\":\"az\",\"url\":\""+url+"\"}").getBytes(StandardCharsets.UTF_8));
  when(db.update(contains("SET state='SENDING'"),eq(job),any(java.sql.Timestamp.class))).thenReturn(1,0);
  when(db.queryForList(anyString(),eq(job))).thenReturn(List.of(Map.of("id",job,"nonce",nonce,"cipher",encrypted,"email","qa@example.com","purpose","VERIFY")));
  if(uncertain)doThrow(new MailSendException("ambiguous test failure")).when(mail).send(any(SimpleMailMessage.class));
  var delivery=service(db,mail,true,"https://example.com");delivery.deliver(job);delivery.deliver(job);
  var message=ArgumentCaptor.forClass(SimpleMailMessage.class);verify(mail,times(1)).send(message.capture());assertTrue(message.getValue().getText().contains(url));assertArrayEquals(new String[]{"qa@example.com"},message.getValue().getTo());
  verify(db).update(contains(uncertain?"state='UNKNOWN'":"state='SENT',cipher=null,nonce=null"),eq(job));
 }
}
