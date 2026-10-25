package az.azertur.operations;

import az.azertur.notification.NotificationDelivery;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operations/notifications")
public class NotificationOperationsController {
  private final NotificationDelivery delivery;

  public NotificationOperationsController(NotificationDelivery delivery) {
    this.delivery = delivery;
  }

  @GetMapping
  public Object pending() {
    return delivery.pending();
  }

  @PostMapping("/{id}/send-email")
  public void send(@PathVariable UUID id) {
    delivery.send(id);
  }
}
