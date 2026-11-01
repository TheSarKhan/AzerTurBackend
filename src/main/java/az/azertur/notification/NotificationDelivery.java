package az.azertur.notification;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationDelivery {
  private final JdbcTemplate db;
  private final JavaMailSender mail;
  private final boolean enabled;
  private final String sender;

  public NotificationDelivery(
      JdbcTemplate db,
      JavaMailSender mail,
      @Value("${app.notifications.email-enabled:false}") boolean enabled,
      @Value("${app.notifications.from:}") String sender) {
    this.db = db;
    this.mail = mail;
    this.enabled = enabled;
    this.sender = sender;
  }

  public Object pending() {
    return db.queryForList(
        "SELECT id,booking_id,channel,event,status,attempts,created_at,sent_at FROM"
            + " notification_outbox ORDER BY created_at DESC LIMIT 200");
  }

  @Transactional
  public void send(UUID id) {
    if (!enabled || sender.isBlank())
      throw az.azertur.shared.DomainException.conflict("EMAIL_NOT_CONFIGURED");
    var rows =
        db.queryForList(
            "SELECT n.*,c.email,c.locale FROM notification_outbox n JOIN customers c ON"
                + " c.id=n.customer_id WHERE n.id=? FOR UPDATE OF n",
            id);
    if (rows.isEmpty()) throw az.azertur.shared.DomainException.missing();
    var n = rows.getFirst();
    if (!n.get("channel").equals("EMAIL"))
      throw az.azertur.shared.DomainException.conflict("PROVIDER_NOT_CONFIGURED");
    if (n.get("status").equals("SENT")) return;
    var message = new SimpleMailMessage();
    message.setFrom(sender);
    message.setTo((String) n.get("email"));
    String locale = (String) n.get("locale");
    String heading =
        locale.equals("az")
            ? "Səfəriniz yeniləndi"
            : locale.equals("ru") ? "Ваша поездка обновлена" : "Your journey has been updated";
    String detail =
        locale.equals("az")
            ? "Son vəziyyəti və ofisin cavabını hesabınızdakı Səfərlərim bölməsində görə"
                  + " bilərsiniz."
            : locale.equals("ru")
                ? "Посмотрите актуальный статус и ответ офиса в разделе Мои поездки."
                : "Open My trips in your account to see the latest status and office response.";
    message.setSubject("Azertur — " + heading);
    message.setText(
        heading
            + "\n\n"
            + detail
            + "\n\n"
            + (locale.equals("az")
                ? "Rezervasiya"
                : locale.equals("ru") ? "Бронирование" : "Reservation")
            + ": "
            + n.get("booking_id"));
    mail.send(message);
    db.update(
        "UPDATE notification_outbox SET status='SENT',attempts=attempts+1,sent_at=now() WHERE id=?",
        id);
  }
}
