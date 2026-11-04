package az.azertur.notification;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import az.azertur.shared.DomainException;
@Service
public class NotificationDelivery {
 private final JdbcTemplate db;private final JavaMailSender mail;private final boolean enabled;private final String sender;private final java.sql.Timestamp notBefore;
 public NotificationDelivery(JdbcTemplate db,JavaMailSender mail,@Value("${app.notifications.email-enabled:false}") boolean enabled,@Value("${app.notifications.from:}") String sender,@Value("${app.notifications.email-not-before:1970-01-01T00:00:00Z}") java.time.Instant notBefore){this.db=db;this.mail=mail;this.enabled=enabled;this.sender=sender;this.notBefore=java.sql.Timestamp.from(notBefore);}
 public Object pending(){return Map.of("emailConfigured",enabled&&!sender.isBlank(),"deliveryNotBefore",notBefore.toInstant(),"items",db.queryForList("SELECT n.id,n.booking_id,n.channel,n.event,n.status,n.attempts,n.created_at,n.sent_at,n.last_error,n.next_attempt_at,c.name AS customer_name FROM notification_outbox n JOIN customers c ON c.id=n.customer_id ORDER BY n.created_at DESC LIMIT 500"));}
 public void retry(UUID id){if(!enabled||sender.isBlank())throw DomainException.conflict("EMAIL_NOT_CONFIGURED");if(db.update("UPDATE notification_outbox SET status='QUEUED',next_attempt_at=now(),last_error='' WHERE id=? AND channel='EMAIL' AND status IN ('FAILED','WAITING_CONFIGURATION') AND created_at>=?",id,notBefore)!=1)throw DomainException.conflict("RETRY_NOT_ALLOWED");}
 public void send(UUID id){
  if(!enabled||sender.isBlank())throw DomainException.conflict("EMAIL_NOT_CONFIGURED");
  // This claim commits before SMTP. A process crash or ambiguous transport failure is not retried automatically.
  if(db.update("UPDATE notification_outbox SET status='DELIVERING',attempts=attempts+1,last_error='',next_attempt_at=now() WHERE id=? AND channel='EMAIL' AND status='QUEUED' AND next_attempt_at<=now() AND created_at>=?",id,notBefore)!=1)return;
  var n=db.queryForMap("SELECT n.booking_id,c.email,c.locale FROM notification_outbox n JOIN customers c ON c.id=n.customer_id WHERE n.id=?",id);
  var message=new SimpleMailMessage();message.setFrom(sender);message.setTo((String)n.get("email"));
  String l=(String)n.get("locale");String title=l.equals("az")?"Səfəriniz yeniləndi":l.equals("ru")?"Ваша поездка обновлена":"Your journey has been updated";
  message.setSubject("Azertur — "+title);message.setText(title+"\n\n"+(l.equals("az")?"Hesabınızda son vəziyyətə baxın.":l.equals("ru")?"Посмотрите обновления в вашем аккаунте.":"Open your account to review the latest update.")+"\n"+n.get("booking_id"));
  try{mail.send(message);db.update("UPDATE notification_outbox SET status='SENT',sent_at=now() WHERE id=? AND status='DELIVERING'",id);}
  catch(MailAuthenticationException|MailParseException e){db.update("UPDATE notification_outbox SET status=CASE WHEN attempts<3 THEN 'QUEUED' ELSE 'FAILED' END,last_error='SMTP_AUTH_OR_MESSAGE',next_attempt_at=now()+interval '5 minutes' WHERE id=? AND status='DELIVERING'",id);}
  catch(Exception e){db.update("UPDATE notification_outbox SET status='DELIVERY_UNKNOWN',last_error='SMTP_RESULT_UNKNOWN' WHERE id=? AND status='DELIVERING'",id);}
 }
 public void tick(){
  if(!enabled||sender.isBlank())return;
  db.update("UPDATE notification_outbox SET status='DELIVERY_UNKNOWN',last_error='WORKER_INTERRUPTED' WHERE channel='EMAIL' AND status='DELIVERING' AND next_attempt_at<now()-interval '2 minutes'");
  db.update("UPDATE notification_outbox SET status='QUEUED' WHERE channel='EMAIL' AND status='WAITING_CONFIGURATION' AND created_at>=?",notBefore);
  for(UUID id:db.queryForList("SELECT id FROM notification_outbox WHERE channel='EMAIL' AND status='QUEUED' AND next_attempt_at<=now() AND created_at>=? ORDER BY created_at LIMIT 10",UUID.class,notBefore))try{send(id);}catch(Exception ignored){}
 }
}
