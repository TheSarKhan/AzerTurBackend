package az.azertur.customer;

import az.azertur.shared.DomainException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@Service
@EnableScheduling
public class AccountSecurityService {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final JavaMailSender mail;
  private final TransactionTemplate tx;
  private final byte[] key;
  private final String origin, sender;
  private final boolean email;
  private final java.sql.Timestamp notBefore;
  private final SecureRandom random = new SecureRandom();

  public AccountSecurityService(JdbcTemplate db, ObjectMapper json, JavaMailSender mail,
      PlatformTransactionManager transactions,
      @Value("${app.security.account-link-key}") String secret,
      @Value("${app.security.portal-origin}") String origin,
      @Value("${app.notifications.email-enabled:false}") boolean email,
      @Value("${app.notifications.from:}") String sender,
      @Value("${app.notifications.email-not-before:1970-01-01T00:00:00Z}") Instant notBefore) {
    this.db=db; this.json=json; this.mail=mail; this.tx=new TransactionTemplate(transactions);
    try { key=Base64.getDecoder().decode(secret); }
    catch (Exception e) { throw new IllegalStateException("ACCOUNT_LINK_KEY must be base64 for 32 random bytes"); }
    if(key.length!=32) throw new IllegalStateException("ACCOUNT_LINK_KEY must contain 32 random bytes");
    URI uri=URI.create(origin);
    if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
        || !(uri.getPath().isEmpty()||uri.getPath().equals("/"))
        || !(uri.getScheme().equals("https") || uri.getScheme().equals("http") && Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost())))
      throw new IllegalStateException("PORTAL_ORIGIN must be a trusted HTTPS origin (HTTP is allowed on loopback only)");
    this.origin=origin.replaceAll("/+$",""); this.email=email; this.sender=sender; this.notBefore=java.sql.Timestamp.from(notBefore);
  }

  private String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch(Exception e) { throw new IllegalStateException(e); }
  }
  private Map<String,Object> customer(UUID id) {
    return db.queryForList("SELECT id,email,locale,email_verified_at FROM customers WHERE id=? FOR UPDATE",id)
        .stream().findFirst().orElseThrow(DomainException::missing);
  }
  private byte[][] encrypt(UUID id, String text) {
    try {
      byte[] nonce=new byte[12]; random.nextBytes(nonce);
      var cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
      cipher.updateAAD(id.toString().getBytes(StandardCharsets.US_ASCII));
      return new byte[][]{cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)),nonce};
    } catch(Exception e) { throw new IllegalStateException("ACCOUNT_MAIL_ENCRYPTION_FAILED"); }
  }
  private String decrypt(Map<String,Object> job) {
    try {
      var cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,(byte[])job.get("nonce")));
      cipher.updateAAD(job.get("id").toString().getBytes(StandardCharsets.US_ASCII));
      return new String(cipher.doFinal((byte[])job.get("cipher")),StandardCharsets.UTF_8);
    } catch(Exception e) { throw new IllegalStateException("ACCOUNT_MAIL_DECRYPTION_FAILED"); }
  }
  private void audit(UUID id,String action) {
    db.update("INSERT INTO customer_security_audit(id,customer_id,action) VALUES(?,?,?)",UUID.randomUUID(),id,action);
  }
  private void queueLink(UUID job,Map<String,Object> c,String purpose) {
    UUID customer=(UUID)c.get("id");
    if(purpose.equals("VERIFY") && c.get("email_verified_at")!=null) { finish(job,"RESOLVED"); return; }
    if(db.queryForObject("SELECT count(*) FROM account_links WHERE customer_id=? AND purpose=? AND consumed_at IS NULL AND expires_at>now() AND created_at>now()-interval '1 hour'",Integer.class,customer,purpose)>=3) {
      finish(job,"RESOLVED"); return;
    }
    UUID link=UUID.randomUUID(); byte[] bytes=new byte[32]; random.nextBytes(bytes);
    String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant expires=Instant.now().plus(purpose.equals("VERIFY")?Duration.ofHours(24):Duration.ofMinutes(30));
    db.update("INSERT INTO account_links(id,customer_id,purpose,token_hash,expires_at) VALUES(?,?,?,?,?)",link,customer,purpose,hash(token),java.sql.Timestamp.from(expires));
    String url=origin+"/"+c.get("locale")+"/account/"+(purpose.equals("VERIFY")?"verify":"reset")+"#token="+token;
    var payload=encrypt(job,json.writeValueAsString(Map.of("url",url,"locale",c.get("locale"))));
    db.update("UPDATE account_mail_jobs SET customer_id=?,link_id=?,purpose=?,cipher=?,nonce=?,state=?,next_attempt_at=now() WHERE id=?",customer,link,purpose,payload[0],payload[1],email&&!sender.isBlank()?"QUEUED":"WAITING_CONFIGURATION",job);
  }
  private void finish(UUID job,String state) {
    db.update("UPDATE account_mail_jobs SET state=?,cipher=null,nonce=null WHERE id=?",state,job);
  }
  @Transactional
  public void verification(UUID id) {
    var c=customer(id);
    if(c.get("email_verified_at")!=null) return;
    if(db.queryForObject("SELECT count(*) FROM account_mail_jobs j JOIN account_links l ON l.id=j.link_id WHERE j.customer_id=? AND j.purpose='VERIFY' AND j.state IN ('QUEUED','WAITING_CONFIGURATION','SENDING') AND l.consumed_at IS NULL AND l.expires_at>now()",Integer.class,id)>0) return;
    UUID job=UUID.randomUUID();
    db.update("INSERT INTO account_mail_jobs(id,customer_id,purpose,state) VALUES(?,?,'VERIFY','QUEUED')",job,id);
    queueLink(job,c,"VERIFY");
  }
  @Transactional
  public void forgot(String email) {
    // Both known and unknown addresses take this same asynchronous, encrypted request path.
    UUID id=UUID.randomUUID(); var payload=encrypt(id,email.trim().toLowerCase(Locale.ROOT));
    db.update("INSERT INTO account_mail_jobs(id,purpose,cipher,nonce,state) VALUES(?,'RESET_REQUEST',?,?,'QUEUED')",id,payload[0],payload[1]);
  }
  private Map<String,Object> usableLink(String token,String purpose) {
    var found=db.queryForList("SELECT customer_id FROM account_links WHERE token_hash=? AND purpose=?",hash(token),purpose);
    if(found.isEmpty()) throw DomainException.conflict("INVALID_OR_EXPIRED_LINK");
    UUID id=(UUID)found.getFirst().get("customer_id"); customer(id);
    return db.queryForList("SELECT * FROM account_links WHERE token_hash=? AND purpose=? AND consumed_at IS NULL AND expires_at>now() FOR UPDATE",hash(token),purpose)
        .stream().findFirst().orElseThrow(()->DomainException.conflict("INVALID_OR_EXPIRED_LINK"));
  }
  private void consumeAll(UUID id) {
    db.update("UPDATE account_links SET consumed_at=now() WHERE customer_id=? AND consumed_at IS NULL",id);
    db.update("UPDATE account_mail_jobs SET state='REVOKED',cipher=null,nonce=null WHERE customer_id=? AND state IN ('QUEUED','WAITING_CONFIGURATION','SENDING','UNKNOWN','FAILED')",id);
  }
  @Transactional
  public void verify(String token) {
    var link=usableLink(token,"VERIFY"); UUID id=(UUID)link.get("customer_id");
    db.update("UPDATE customers SET email_verified_at=coalesce(email_verified_at,now()) WHERE id=?",id);
    db.update("UPDATE account_links SET consumed_at=now() WHERE customer_id=? AND purpose='VERIFY' AND consumed_at IS NULL",id);
    db.update("UPDATE account_mail_jobs SET state='REVOKED',cipher=null,nonce=null WHERE customer_id=? AND purpose='VERIFY' AND state<>'SENT'",id);
    audit(id,"EMAIL_VERIFIED");
  }
  @Transactional
  public void reset(String token,String password,String confirmation) {
    if(!password.equals(confirmation)) throw DomainException.bad("PASSWORDS_DO_NOT_MATCH");
    if(password.getBytes(StandardCharsets.UTF_8).length>72) throw DomainException.bad("PASSWORD_TOO_LONG");
    var link=usableLink(token,"RESET"); UUID id=(UUID)link.get("customer_id");
    db.update("UPDATE customers SET password_hash=? WHERE id=?",new BCryptPasswordEncoder(12).encode(password),id);
    consumeAll(id);
    db.update("DELETE FROM customer_sessions WHERE customer_id=?",id);
    db.update("UPDATE customer_push_tokens SET enabled=false WHERE customer_id=?",id);
    audit(id,"PASSWORD_RESET");
    UUID job=UUID.randomUUID(); var payload=encrypt(job,json.writeValueAsString(Map.of("locale",customer(id).get("locale"))));
    db.update("INSERT INTO account_mail_jobs(id,customer_id,purpose,cipher,nonce,state) VALUES(?,?,'PASSWORD_CHANGED',?,?,?)",job,id,payload[0],payload[1],email&&!sender.isBlank()?"QUEUED":"WAITING_CONFIGURATION");
  }
  public Object sessions(UUID id,String currentToken) {
    return db.queryForList("SELECT id,device,created_at,last_seen_at,expires_at,(token_hash=?) AS current FROM customer_sessions WHERE customer_id=? AND expires_at>now() ORDER BY created_at DESC",hash(currentToken),id);
  }
  @Transactional
  public void revoke(UUID id,UUID session,String currentToken) {
    customer(id);
    var rows=db.queryForList("SELECT token_hash FROM customer_sessions WHERE id=? AND customer_id=?",session,id);
    if(rows.isEmpty()) throw DomainException.missing();
    if(rows.getFirst().get("token_hash").equals(hash(currentToken))) throw DomainException.conflict("USE_SIGN_OUT_FOR_CURRENT_SESSION");
    db.update("DELETE FROM customer_sessions WHERE id=? AND customer_id=?",session,id); audit(id,"SESSION_REVOKED");
  }
  @Transactional
  public void revokeOthers(UUID id,String currentToken) {
    customer(id); db.update("DELETE FROM customer_sessions WHERE customer_id=? AND token_hash<>?",id,hash(currentToken)); audit(id,"OTHER_SESSIONS_REVOKED");
  }
  @Transactional
  public void revokeAll(UUID id) {
    customer(id); db.update("DELETE FROM customer_sessions WHERE customer_id=?",id); audit(id,"ALL_SESSIONS_REVOKED");
  }
  @Scheduled(fixedDelay=15000)
  public void tick() {
    // Resolve reset requests even while SMTP is disabled; neither API timing nor response reveals account existence.
    for(UUID id:db.queryForList("SELECT id FROM account_mail_jobs WHERE purpose='RESET_REQUEST' AND state='QUEUED' ORDER BY created_at LIMIT 10",UUID.class)) {
      try { tx.executeWithoutResult(status->{
        var jobs=db.queryForList("SELECT * FROM account_mail_jobs WHERE id=? AND purpose='RESET_REQUEST' AND state='QUEUED' FOR UPDATE SKIP LOCKED",id);
        if(jobs.isEmpty()) return;
        String address=decrypt(jobs.getFirst());
        var found=db.queryForList("SELECT id FROM customers WHERE email=?",address);
        if(found.isEmpty()) finish(id,"RESOLVED");
        else queueLink(id,customer((UUID)found.getFirst().get("id")),"RESET");
      }); } catch(Exception failure) { finish(id,"FAILED"); }
    }
    db.update("UPDATE account_mail_jobs j SET state='REVOKED',cipher=null,nonce=null WHERE link_id IS NOT NULL AND EXISTS(SELECT 1 FROM account_links l WHERE l.id=j.link_id AND (l.consumed_at IS NOT NULL OR l.expires_at<=now())) AND j.cipher IS NOT NULL");
    if(!email || sender.isBlank()) return;
    db.update("UPDATE account_mail_jobs SET state='UNKNOWN' WHERE state='SENDING' AND next_attempt_at<now()-interval '2 minutes'");
    db.update("UPDATE account_mail_jobs SET state='QUEUED' WHERE state='WAITING_CONFIGURATION' AND created_at>=?",notBefore);
    for(UUID id:db.queryForList("SELECT id FROM account_mail_jobs WHERE purpose<>'RESET_REQUEST' AND state='QUEUED' AND next_attempt_at<=now() AND created_at>=? ORDER BY created_at LIMIT 10",UUID.class,notBefore)) deliver(id);
  }
  public void deliver(UUID id) {
    if(!email||sender.isBlank()) return;
    if(db.update("UPDATE account_mail_jobs SET state='SENDING',attempts=attempts+1,next_attempt_at=now() WHERE id=? AND state='QUEUED' AND purpose<>'RESET_REQUEST' AND cipher IS NOT NULL AND created_at>=?",id,notBefore)!=1) return;
    var jobs=db.queryForList("SELECT j.*,c.email FROM account_mail_jobs j JOIN customers c ON c.id=j.customer_id WHERE j.id=? AND j.state='SENDING' AND j.cipher IS NOT NULL",id);
    if(jobs.isEmpty()) return;
    try {
      var job=jobs.getFirst();var p=json.readTree(decrypt(job));String l=p.path("locale").asText();String purpose=(String)job.get("purpose");
      String title=purpose.equals("VERIFY")?(l.equals("az")?"Email ünvanınızı təsdiqləyin":l.equals("ru")?"Подтвердите email":"Verify your email")
          :purpose.equals("RESET")?(l.equals("az")?"Şifrənizi yeniləyin":l.equals("ru")?"Сброс пароля":"Reset your password")
          :(l.equals("az")?"Şifrəniz dəyişdirildi":l.equals("ru")?"Пароль изменён":"Your password was changed");
      var message=new SimpleMailMessage();message.setFrom(sender);message.setTo((String)job.get("email"));message.setSubject("Azertur — "+title);
      message.setText(title+"\n\n"+(purpose.equals("PASSWORD_CHANGED")?(l.equals("az")?"Bu dəyişikliyi siz etməmisinizsə komandamızla əlaqə saxlayın.":l.equals("ru")?"Если это были не вы, свяжитесь с нашей командой.":"If this was not you, contact our team."):p.path("url").asText()+"\n\n"+(l.equals("az")?"Bu linki siz istəməmisinizsə, məktubu nəzərə almayın.":l.equals("ru")?"Если вы не запрашивали ссылку, проигнорируйте письмо.":"If you did not request this link, ignore this message.")));
      mail.send(message);
      db.update("UPDATE account_mail_jobs SET state='SENT',cipher=null,nonce=null WHERE id=? AND state='SENDING'",id);
    } catch(MailAuthenticationException|MailParseException failure) {
      db.update("UPDATE account_mail_jobs SET state=CASE WHEN attempts<3 THEN 'QUEUED' ELSE 'FAILED' END,next_attempt_at=now()+interval '5 minutes' WHERE id=? AND state='SENDING'",id);
    } catch(Exception failure) {
      db.update("UPDATE account_mail_jobs SET state='UNKNOWN' WHERE id=? AND state='SENDING'",id);
    }
  }
}
