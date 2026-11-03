package az.azertur.notification;
import az.azertur.customer.CustomerAuthService.Customer;
import az.azertur.shared.DomainException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/bookings/notifications")
public class CustomerNotifications {
 private final JdbcTemplate db;public CustomerNotifications(JdbcTemplate db){this.db=db;}
 @GetMapping Object list(@AuthenticationPrincipal Customer c){return Map.of("unread",db.queryForObject("SELECT count(*) FROM customer_notifications WHERE customer_id=? AND read_at IS NULL",Integer.class,c.id()),"items",db.queryForList("SELECT id,booking_id,event,read_at,created_at FROM customer_notifications WHERE customer_id=? ORDER BY created_at DESC LIMIT 200",c.id()));}
 @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
 @PostMapping("/{id}/read") void read(@PathVariable UUID id,@AuthenticationPrincipal Customer c){if(db.update("UPDATE customer_notifications SET read_at=coalesce(read_at,now()) WHERE id=? AND customer_id=?",id,c.id())!=1)throw DomainException.missing();}
 @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
 @PostMapping("/read-all") void readAll(@AuthenticationPrincipal Customer c){db.update("UPDATE customer_notifications SET read_at=now() WHERE customer_id=? AND read_at IS NULL",c.id());}
}
