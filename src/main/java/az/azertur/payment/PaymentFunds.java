package az.azertur.payment;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
public final class PaymentFunds {
  private PaymentFunds(){}
  public static long received(JdbcTemplate db,UUID id){
    return db.queryForObject("SELECT paid::bigint*100 FROM bookings WHERE id=?",Long.class,id)
        +db.queryForObject("SELECT coalesce(sum(amount_minor),0) FROM staged_payment_requests WHERE booking_id=? AND status='PAID'",Long.class,id);
  }
  public static long retired(JdbcTemplate db,UUID id){
    return db.queryForObject("SELECT coalesce(sum(allocated_minor),0) FROM cancelled_services WHERE booking_id=?",Long.class,id)
        +db.queryForObject("SELECT coalesce(sum(retired_minor),0) FROM amendment_settlements WHERE booking_id=?",Long.class,id);
  }
  public static long active(JdbcTemplate db,UUID id){return Math.subtractExact(received(db,id),retired(db,id));}
}
