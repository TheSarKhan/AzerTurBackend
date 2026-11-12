package az.azertur.contact;

import az.azertur.shared.DomainException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Stores contact-form messages and sends a best-effort copy to the team inbox. */
@Service
public class ContactService {
  private static final Logger log = LoggerFactory.getLogger(ContactService.class);
  private final JdbcTemplate db;
  private final JavaMailSender mail;
  private final boolean emailEnabled;
  private final String sender;
  private final String inbox;

  public ContactService(
      JdbcTemplate db,
      JavaMailSender mail,
      @Value("${app.notifications.email-enabled:false}") boolean emailEnabled,
      @Value("${app.notifications.from:}") String sender,
      @Value("${app.contact.inbox:}") String inbox) {
    this.db = db;
    this.mail = mail;
    this.emailEnabled = emailEnabled;
    this.sender = sender;
    this.inbox = inbox == null ? "" : inbox.strip();
  }

  public void submit(String name, String email, String topic, String message, String locale) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO contact_messages(id,name,email,topic,message,locale) VALUES(?,?,?,?,?,?)",
        id, name.strip(), email.strip(), topic, message.strip(), locale);
    String state = "SKIPPED";
    if (emailEnabled && !sender.isBlank() && !inbox.isBlank()) {
      try {
        var m = new SimpleMailMessage();
        m.setFrom(sender);
        m.setTo(inbox);
        m.setReplyTo(email.strip());
        m.setSubject("AzTrip — new message (" + topic + ")");
        m.setText(name.strip() + " <" + email.strip() + ">\n\n" + message.strip() + "\n\n" + id);
        mail.send(m);
        state = "SENT";
      } catch (Exception e) {
        state = "FAILED";
        log.warn("Contact message {} could not be emailed to the team inbox", id);
      }
    }
    db.update("UPDATE contact_messages SET email_state=? WHERE id=?", state, id);
  }

  public List<Map<String, Object>> list() {
    return db.queryForList(
        "SELECT id,name,email,topic,message,locale,state,email_state,created_at,handled_by,handled_at"
            + " FROM contact_messages ORDER BY (state='NEW') DESC, created_at DESC LIMIT 200");
  }

  public Object handled(UUID id, String actor) {
    int n =
        db.update(
            "UPDATE contact_messages SET state='HANDLED',handled_by=?,handled_at=now() WHERE id=? AND state='NEW'",
            actor, id);
    if (n == 0) {
      if (db.queryForObject("SELECT count(*) FROM contact_messages WHERE id=?", Integer.class, id) == 0)
        throw DomainException.missing();
      throw DomainException.conflict("ALREADY_HANDLED");
    }
    return list();
  }
}
