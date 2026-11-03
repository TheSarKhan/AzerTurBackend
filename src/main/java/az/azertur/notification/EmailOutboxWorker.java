package az.azertur.notification;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
@Configuration @EnableScheduling
@ConditionalOnProperty(name="app.notifications.email-enabled",havingValue="true")
public class EmailOutboxWorker {
 private final NotificationDelivery delivery;public EmailOutboxWorker(NotificationDelivery delivery){this.delivery=delivery;}
 @Scheduled(fixedDelay=15000) public void deliver(){delivery.tick();}
}
