package az.azertur.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import az.azertur.customer.AccountSecurityService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import tools.jackson.databind.ObjectMapper;

/** Opt-in isolated PostgreSQL check. The transport is mocked: no message leaves the process. */
@EnabledIfEnvironmentVariable(named="AZERTUR_MAIL_CUTOVER_TEST",matches="1")
class MailCutoverIntegrationTest {
 @Test void historicalJobsCannotSendOrPromoteButNewJobsSendOnce() {
  String url=System.getenv("MAIL_CUTOVER_JDBC_URL");
  assertNotNull(url);
  assertTrue(url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/azertur_qa_mail_cutover_[a-z0-9_]+"),"Dedicated loopback QA database required");
  var ds=new DriverManagerDataSource(url,"azertur",System.getenv("DATABASE_PASSWORD"));
  Flyway.configure().dataSource(ds).load().migrate();
  var db=new JdbcTemplate(ds);var mail=mock(JavaMailSender.class);
  Instant cutoff=Instant.now().plusSeconds(300);UUID customer=UUID.randomUUID(),oldNotice=UUID.randomUUID(),newNotice=UUID.randomUUID();
  db.update("INSERT INTO customers(id,email,name,password_hash,locale) VALUES(?,?,'Cutover QA','not-a-real-password-hash','en')",customer,"cutover-"+customer+"@example.com");
  for(UUID id:List.of(oldNotice,newNotice))db.update("INSERT INTO notification_outbox(id,customer_id,channel,event,body,status,created_at) VALUES(?,?,'EMAIL','MAIL_CUTOVER_QA','QA only','QUEUED',?)",id,customer,Timestamp.from(cutoff.plusSeconds(id.equals(oldNotice)?-1:1)));
  var delivery=new NotificationDelivery(db,mail,true,"qa@example.com",cutoff);
  delivery.send(oldNotice);verifyNoInteractions(mail);
  delivery.send(newNotice);delivery.send(newNotice);verify(mail,times(1)).send(any(SimpleMailMessage.class));
  assertEquals("QUEUED",db.queryForObject("SELECT status FROM notification_outbox WHERE id=?",String.class,oldNotice));
  db.update("UPDATE notification_outbox SET status='WAITING_CONFIGURATION' WHERE id=?",oldNotice);
  delivery.tick();assertEquals("WAITING_CONFIGURATION",db.queryForObject("SELECT status FROM notification_outbox WHERE id=?",String.class,oldNotice));
  verify(mail,times(1)).send(any(SimpleMailMessage.class));

  var security=new AccountSecurityService(db,new ObjectMapper(),mail,new DataSourceTransactionManager(ds),Base64.getEncoder().encodeToString(new byte[32]),"http://127.0.0.1:4182",true,"qa@example.com",cutoff);
  UUID oldCustomer=UUID.randomUUID(),newCustomer=UUID.randomUUID();
  for(UUID id:List.of(oldCustomer,newCustomer))db.update("INSERT INTO customers(id,email,name,password_hash,locale) VALUES(?,?,'Account cutover QA','not-a-real-password-hash','en')",id,"account-cutover-"+id+"@example.com");
  security.verification(oldCustomer);security.verification(newCustomer);
  UUID oldJob=db.queryForObject("SELECT id FROM account_mail_jobs WHERE customer_id=?",UUID.class,oldCustomer),newJob=db.queryForObject("SELECT id FROM account_mail_jobs WHERE customer_id=?",UUID.class,newCustomer);
  db.update("UPDATE account_mail_jobs SET created_at=? WHERE id=?",Timestamp.from(cutoff.minusSeconds(1)),oldJob);
  db.update("UPDATE account_mail_jobs SET created_at=? WHERE id=?",Timestamp.from(cutoff.plusSeconds(1)),newJob);
  security.deliver(oldJob);verify(mail,times(1)).send(any(SimpleMailMessage.class));
  db.update("UPDATE account_mail_jobs SET state='WAITING_CONFIGURATION' WHERE id IN (?,?)",oldJob,newJob);
  security.tick();security.tick();
  assertEquals("WAITING_CONFIGURATION",db.queryForObject("SELECT state FROM account_mail_jobs WHERE id=?",String.class,oldJob));
  assertEquals("SENT",db.queryForObject("SELECT state FROM account_mail_jobs WHERE id=?",String.class,newJob));
  verify(mail,times(2)).send(any(SimpleMailMessage.class));
 }
}
