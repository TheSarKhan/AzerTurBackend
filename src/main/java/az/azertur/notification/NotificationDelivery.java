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
 private final JdbcTemplate db;private final JavaMailSender mail;private final boolean enabled;private final String sender;private final java.sql.Timestamp notBefore;@Value("${app.notifications.site-origin:https://aztrip.online}") private String siteOrigin="https://aztrip.online";
 public NotificationDelivery(JdbcTemplate db,JavaMailSender mail,@Value("${app.notifications.email-enabled:false}") boolean enabled,@Value("${app.notifications.from:}") String sender,@Value("${app.notifications.email-not-before:1970-01-01T00:00:00Z}") java.time.Instant notBefore){this.db=db;this.mail=mail;this.enabled=enabled;this.sender=sender;this.notBefore=java.sql.Timestamp.from(notBefore);}
 public Object pending(){return Map.of("emailConfigured",enabled&&!sender.isBlank(),"deliveryNotBefore",notBefore.toInstant(),"items",db.queryForList("SELECT n.id,n.booking_id,n.channel,n.event,n.status,n.attempts,n.created_at,n.sent_at,n.last_error,n.next_attempt_at,c.name AS customer_name FROM notification_outbox n JOIN customers c ON c.id=n.customer_id ORDER BY n.created_at DESC LIMIT 500"));}
 public void retry(UUID id){if(!enabled||sender.isBlank())throw DomainException.conflict("EMAIL_NOT_CONFIGURED");if(db.update("UPDATE notification_outbox SET status='QUEUED',next_attempt_at=now(),last_error='' WHERE id=? AND channel='EMAIL' AND status IN ('FAILED','WAITING_CONFIGURATION') AND created_at>=?",id,notBefore)!=1)throw DomainException.conflict("RETRY_NOT_ALLOWED");}
 public void send(UUID id){
  if(!enabled||sender.isBlank())throw DomainException.conflict("EMAIL_NOT_CONFIGURED");
  // This claim commits before SMTP. A process crash or ambiguous transport failure is not retried automatically.
  if(db.update("UPDATE notification_outbox SET status='DELIVERING',attempts=attempts+1,last_error='',next_attempt_at=now() WHERE id=? AND channel='EMAIL' AND status='QUEUED' AND next_attempt_at<=now() AND created_at>=?",id,notBefore)!=1)return;
  var n=db.queryForMap("SELECT n.booking_id,n.event,c.name,c.email,c.locale FROM notification_outbox n JOIN customers c ON c.id=n.customer_id WHERE n.id=?",id);
  var message=new SimpleMailMessage();message.setFrom(sender);message.setTo((String)n.get("email"));
  String l=(String)n.get("locale");
  if("OFFER_SENT".equals(n.get("event")))offerMail(message,n,l);
  else{String title=l.equals("az")?"Səfəriniz yeniləndi":l.equals("ru")?"Ваша поездка обновлена":"Your journey has been updated";
  message.setSubject("AzTrip — "+title);message.setText(title+"\n\n"+(l.equals("az")?"Hesabınızda son vəziyyətə baxın.":l.equals("ru")?"Посмотрите обновления в вашем аккаунте.":"Open your account to review the latest update.")+"\n"+n.get("booking_id"));}
  try{mail.send(message);db.update("UPDATE notification_outbox SET status='SENT',sent_at=now() WHERE id=? AND status='DELIVERING'",id);}
  catch(MailAuthenticationException|MailParseException e){db.update("UPDATE notification_outbox SET status=CASE WHEN attempts<3 THEN 'QUEUED' ELSE 'FAILED' END,last_error='SMTP_AUTH_OR_MESSAGE',next_attempt_at=now()+interval '5 minutes' WHERE id=? AND status='DELIVERING'",id);}
  catch(Exception e){db.update("UPDATE notification_outbox SET status='DELIVERY_UNKNOWN',last_error='SMTP_RESULT_UNKNOWN' WHERE id=? AND status='DELIVERING'",id);}
 }
 /** A short mail for a sent offer: who it is for, the total, and one link to the offer in the customer's account. */
 private void offerMail(SimpleMailMessage message,Map<String,Object> n,String l){
  var offer=db.queryForList("SELECT payload::json->>'total' AS total,payload::json->>'currency' AS currency,payload::json->>'title' AS title FROM offer_revisions WHERE booking_id=? ORDER BY revision DESC LIMIT 1",n.get("booking_id"));
  String link=siteOrigin+"/"+l+"/trips/"+n.get("booking_id");
  String name=n.get("name")==null?"":String.valueOf(n.get("name"));
  String summary=offer.isEmpty()?"":(l.equals("az")?"Səfər: ":l.equals("ru")?"Поездка: ":"Journey: ")+offer.get(0).get("title")+"\n"+(l.equals("az")?"Cəmi: ":l.equals("ru")?"Итого: ":"Total: ")+offer.get(0).get("total")+" "+offer.get(0).get("currency")+"\n\n";
  if(l.equals("az")){message.setSubject("AzTrip — Sizin üçün təklif hazırdır");message.setText("Salam, "+name+"!\n\nSorğunuza görə sizin üçün fərdi təklif hazırladıq.\n\n"+summary+"Təklifə baxmaq, təsdiqləmək və ya sual vermək üçün hesabınıza daxil olun:\n"+link+"\n\nBu mərhələdə ödəniş tələb olunmur.\n\nAzTrip komandası");}
  else if(l.equals("ru")){message.setSubject("AzTrip — Ваше предложение готово");message.setText("Здравствуйте, "+name+"!\n\nМы подготовили для вас персональное предложение по вашей заявке.\n\n"+summary+"Откройте его в личном кабинете, чтобы посмотреть, принять или задать вопрос:\n"+link+"\n\nНа этом этапе оплата не требуется.\n\nКоманда AzTrip");}
  else{message.setSubject("AzTrip — Your offer is ready");message.setText("Hello "+name+",\n\nWe have prepared a personal offer for your request.\n\n"+summary+"Open your account to review it, accept it or ask a question:\n"+link+"\n\nNo payment is requested at this stage.\n\nThe AzTrip team");}
 }
 public void tick(){
  if(!enabled||sender.isBlank())return;
  db.update("UPDATE notification_outbox SET status='DELIVERY_UNKNOWN',last_error='WORKER_INTERRUPTED' WHERE channel='EMAIL' AND status='DELIVERING' AND next_attempt_at<now()-interval '2 minutes'");
  db.update("UPDATE notification_outbox SET status='QUEUED' WHERE channel='EMAIL' AND status='WAITING_CONFIGURATION' AND created_at>=?",notBefore);
  for(UUID id:db.queryForList("SELECT id FROM notification_outbox WHERE channel='EMAIL' AND status='QUEUED' AND next_attempt_at<=now() AND created_at>=? ORDER BY created_at LIMIT 10",UUID.class,notBefore))try{send(id);}catch(Exception ignored){}
 }
}
