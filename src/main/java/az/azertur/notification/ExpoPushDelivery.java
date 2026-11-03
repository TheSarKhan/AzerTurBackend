package az.azertur.notification;
import az.azertur.customer.CustomerAuthService.Customer;
import az.azertur.shared.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
@Service @EnableScheduling
public class ExpoPushDelivery {
 private final JdbcTemplate db;private final ObjectMapper json;private final boolean enabled;private final String key;private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
 public ExpoPushDelivery(JdbcTemplate db,ObjectMapper json,@Value("${app.notifications.push-enabled:false}") boolean enabled,@Value("${app.notifications.expo-access-token:}") String key){this.db=db;this.json=json;this.enabled=enabled;this.key=key;}
 public record Token(@NotNull @Pattern(regexp="(ExponentPushToken|ExpoPushToken)\\[[a-zA-Z0-9_-]{10,200}\\]") String token,boolean enabled){}
 @Transactional public void token(UUID customer,Token input,String bearer){
  String session;try{session=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bearer.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
  var prior=db.queryForList("SELECT customer_id,session_hash FROM customer_push_tokens WHERE token=? FOR UPDATE",input.token());
  if(!prior.isEmpty()&&!prior.getFirst().get("customer_id").equals(customer)&&db.queryForObject("SELECT count(*) FROM customer_sessions WHERE token_hash=? AND expires_at>now()",Integer.class,prior.getFirst().get("session_hash"))>0)throw DomainException.conflict("TOKEN_BELONGS_TO_ANOTHER_ACCOUNT");
  db.update("INSERT INTO customer_push_tokens(token,customer_id,enabled,session_hash) VALUES(?,?,?,?) ON CONFLICT(token) DO UPDATE SET customer_id=excluded.customer_id,enabled=excluded.enabled,session_hash=excluded.session_hash",input.token(),customer,input.enabled(),session);
 }

 public boolean configured(){return enabled;}
 @Transactional public void retry(UUID id){
  if(!enabled)throw DomainException.conflict("PUSH_NOT_CONFIGURED");
  if(db.update("UPDATE notification_outbox SET status='QUEUED' WHERE id=? AND channel='PUSH' AND status='FAILED'",id)!=1)throw DomainException.conflict("RETRY_NOT_ALLOWED");
  int changed=db.update("UPDATE push_deliveries p SET state='QUEUED',next_attempt_at=now(),updated_at=now() WHERE outbox_id=? AND state='FAILED' AND EXISTS(SELECT 1 FROM customer_push_tokens t JOIN customer_sessions s ON s.token_hash=t.session_hash JOIN notification_outbox n ON n.id=p.outbox_id WHERE t.token=p.token AND t.enabled=true AND s.expires_at>now() AND t.customer_id=n.customer_id)",id);
  if(changed==0)throw DomainException.conflict("NO_ACTIVE_PUSH_DEVICE");
 }
 private static class RetryablePush extends Exception{}
 private tools.jackson.databind.JsonNode post(String path,Object body) throws Exception {var builder=HttpRequest.newBuilder(URI.create("https://exp.host/--/api/v2/push/"+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");if(!key.isBlank())builder.header("Authorization","Bearer "+key);var response=client.send(builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()==429)throw new RetryablePush();if(response.statusCode()!=200)throw new java.io.IOException("PUSH_HTTP_RESULT_UNKNOWN");return json.readTree(response.body()).path("data");}
 @Scheduled(fixedDelay=30000) public void tick(){
  if(!enabled)return;
  db.update("UPDATE push_deliveries SET state='UNKNOWN',updated_at=now() WHERE state='SENDING' AND updated_at<now()-interval '2 minutes'");
  db.update("INSERT INTO push_deliveries(id,outbox_id,token) SELECT gen_random_uuid(),n.id,t.token FROM notification_outbox n JOIN customer_push_tokens t ON n.customer_id=t.customer_id AND t.enabled=true AND EXISTS(SELECT 1 FROM customer_sessions cs WHERE cs.token_hash=t.session_hash AND cs.expires_at>now()) WHERE n.channel='PUSH' AND n.status IN ('WAITING_CONFIGURATION','WAITING_DEVICE','QUEUED') ON CONFLICT(outbox_id,token) DO NOTHING");
  db.update("UPDATE notification_outbox SET status='WAITING_DEVICE' WHERE channel='PUSH' AND status IN ('WAITING_CONFIGURATION','QUEUED') AND NOT EXISTS(SELECT 1 FROM push_deliveries p WHERE p.outbox_id=notification_outbox.id)");
  db.update("UPDATE push_deliveries p SET state='CANCELLED',updated_at=now() WHERE state='QUEUED' AND NOT EXISTS(SELECT 1 FROM customer_push_tokens t JOIN customer_sessions s ON s.token_hash=t.session_hash JOIN notification_outbox n ON n.customer_id=t.customer_id WHERE t.token=p.token AND t.enabled=true AND s.expires_at>now() AND n.id=p.outbox_id)");
  for(var row:db.queryForList("SELECT p.*,n.booking_id,c.locale FROM push_deliveries p JOIN notification_outbox n ON n.id=p.outbox_id JOIN customers c ON c.id=n.customer_id WHERE p.state='QUEUED' AND p.next_attempt_at<=now() ORDER BY p.updated_at LIMIT 10")){
   UUID id=(UUID)row.get("id");if(db.update("UPDATE push_deliveries SET state='SENDING',attempts=attempts+1,updated_at=now() WHERE id=? AND state='QUEUED'",id)!=1)continue;
   try{var result=post("send",Map.of("to",row.get("token"),"title","Azertur","body","az".equals(row.get("locale"))?"Səfər yeniliyinə baxmaq üçün hesabınızı açın.":"ru".equals(row.get("locale"))?"Откройте аккаунт, чтобы посмотреть обновление поездки.":"Open your account to review a journey update.","data",Map.of("bookingId",row.get("booking_id"))));
    if(result.path("status").asText().equals("ok")&&!result.path("id").asText().isBlank())db.update("UPDATE push_deliveries SET state='ACCEPTED',ticket_id=?,next_attempt_at=now()+interval '15 minutes',updated_at=now() WHERE id=?",result.path("id").asText(),id);
    else{if(result.path("details").path("error").asText().equals("MessageRateExceeded"))db.update("UPDATE push_deliveries SET state=CASE WHEN attempts<3 THEN 'QUEUED' ELSE 'FAILED' END,next_attempt_at=now()+interval '5 minutes',updated_at=now() WHERE id=?",id);else db.update("UPDATE push_deliveries SET state='FAILED',updated_at=now() WHERE id=?",id);if(result.path("details").path("error").asText().equals("DeviceNotRegistered"))db.update("UPDATE customer_push_tokens SET enabled=false WHERE token=?",row.get("token"));}
   }catch(RetryablePush retry){db.update("UPDATE push_deliveries SET state=CASE WHEN attempts<3 THEN 'QUEUED' ELSE 'FAILED' END,next_attempt_at=now()+interval '5 minutes',updated_at=now() WHERE id=?",id);}
   catch(Exception failure){db.update("UPDATE push_deliveries SET state='UNKNOWN',updated_at=now() WHERE id=?",id);}
  }
  for(var row:db.queryForList("SELECT * FROM push_deliveries WHERE state='ACCEPTED' AND next_attempt_at<=now() LIMIT 20"))try{var result=post("getReceipts",Map.of("ids",List.of(row.get("ticket_id")))).path((String)row.get("ticket_id"));if(!result.isMissingNode()){db.update("UPDATE push_deliveries SET state=?,updated_at=now() WHERE id=?",result.path("status").asText().equals("ok")?"PROVIDER_CONFIRMED":"FAILED",row.get("id"));if(result.path("details").path("error").asText().equals("DeviceNotRegistered"))db.update("UPDATE customer_push_tokens SET enabled=false WHERE token=?",row.get("token"));}else db.update("UPDATE push_deliveries SET state=CASE WHEN updated_at<now()-interval '24 hours' THEN 'UNKNOWN' ELSE state END,next_attempt_at=now()+interval '15 minutes' WHERE id=?",row.get("id"));}catch(Exception failure){db.update("UPDATE push_deliveries SET next_attempt_at=now()+interval '15 minutes' WHERE id=?",row.get("id"));}
  db.update("UPDATE notification_outbox n SET status=CASE WHEN EXISTS(SELECT 1 FROM push_deliveries p WHERE p.outbox_id=n.id AND p.state IN ('SENDING','QUEUED')) THEN 'QUEUED' WHEN EXISTS(SELECT 1 FROM push_deliveries p WHERE p.outbox_id=n.id AND p.state='UNKNOWN') THEN 'DELIVERY_UNKNOWN' WHEN EXISTS(SELECT 1 FROM push_deliveries p WHERE p.outbox_id=n.id AND p.state='ACCEPTED') THEN 'ACCEPTED' WHEN EXISTS(SELECT 1 FROM push_deliveries p WHERE p.outbox_id=n.id AND p.state='FAILED') THEN 'FAILED' WHEN EXISTS(SELECT 1 FROM push_deliveries p WHERE p.outbox_id=n.id AND p.state='CANCELLED') THEN 'CANCELLED' ELSE 'PROVIDER_CONFIRMED' END, attempts=(SELECT coalesce(sum(attempts),0) FROM push_deliveries p WHERE p.outbox_id=n.id) WHERE n.channel='PUSH' AND EXISTS(SELECT 1 FROM push_deliveries p WHERE p.outbox_id=n.id)");
 }
}
@RestController class PushTokenController {
 private final ExpoPushDelivery delivery;PushTokenController(ExpoPushDelivery delivery){this.delivery=delivery;}
 @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
 @PostMapping("/api/v1/bookings/notifications/push-token") void register(@AuthenticationPrincipal Customer c,@RequestHeader("Authorization") String bearer,@Valid @RequestBody ExpoPushDelivery.Token t){delivery.token(c.id(),t,bearer.substring(7));}
}
