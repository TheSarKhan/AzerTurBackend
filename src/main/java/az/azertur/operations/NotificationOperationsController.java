package az.azertur.operations;

import az.azertur.notification.NotificationDelivery;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operations/notifications")
public class NotificationOperationsController {
  private final NotificationDelivery delivery;
  private final az.azertur.notification.ExpoPushDelivery push;

  public NotificationOperationsController(NotificationDelivery delivery,az.azertur.notification.ExpoPushDelivery push) {
    this.push=push;
    this.delivery = delivery;
  }

  @GetMapping
  public Object pending() {
    var result=new java.util.LinkedHashMap<String,Object>((java.util.Map<String,Object>)delivery.pending());result.put("pushConfigured",push.configured());return result;
  }

  @PostMapping("/{id}/retry-push")
  public void retryPush(@PathVariable UUID id){push.retry(id);}

  @PostMapping("/{id}/retry")
  public void retry(@PathVariable UUID id) { delivery.retry(id); }

  @PostMapping("/{id}/send-email")
  public void send(@PathVariable UUID id) {
    delivery.send(id);
  }
}
