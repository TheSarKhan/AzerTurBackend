package az.azertur.notification;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.notifications.email-enabled", havingValue = "true")
public class EmailOutboxWorker {
  private final JdbcTemplate db;
  private final NotificationDelivery delivery;

  public EmailOutboxWorker(JdbcTemplate db, NotificationDelivery delivery) {
    this.db = db;
    this.delivery = delivery;
  }

  @Scheduled(fixedDelay = 15000)
  public void deliver() {
    var jobs =
        db.queryForList(
            "SELECT id FROM notification_outbox WHERE channel='EMAIL' AND status='QUEUED' ORDER BY"
                + " created_at LIMIT 10",
            UUID.class);
    for (UUID id : jobs)
      try {
        delivery.send(id);
      } catch (Exception failure) {
        db.update(
            "UPDATE notification_outbox SET attempts=attempts+1,status=CASE WHEN attempts>=2 THEN"
                + " 'FAILED' ELSE 'QUEUED' END WHERE id=? AND status<>'SENT'",
            id);
      }
  }
}
