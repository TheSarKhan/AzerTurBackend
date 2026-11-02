package az.azertur.payment;

import az.azertur.shared.DomainException;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class PaymentRequestService {
  public record Issue(@NotNull UUID contractId,@NotNull UUID requestKey,
      @NotNull @Pattern(regexp="DEPOSIT|SERVICE") String kind,
      @Size(max=30) String serviceKey,@NotNull BigDecimal amount) {}
  private final JdbcTemplate db;
  private final ObjectMapper json;
  public PaymentRequestService(JdbcTemplate db,ObjectMapper json) { this.db=db; this.json=json; }

  public Object customer(UUID booking,UUID customer) {
    if (db.queryForObject("SELECT count(*) FROM bookings WHERE id=? AND customer_id=?",Integer.class,booking,customer)==0)
      throw DomainException.missing();
    return list(booking);
  }
  public Object list(UUID booking) {
    return db.queryForList("SELECT id,contract_id,kind,service_key,amount_minor,currency,status,created_at,customer_message FROM staged_payment_requests WHERE booking_id=? ORDER BY created_at",booking);
  }
  @Transactional
  public Object issue(UUID booking,Issue input,String actor) {
    var b=db.queryForList("SELECT * FROM bookings WHERE id=? FOR UPDATE",booking).stream().findFirst().orElseThrow(DomainException::missing);
    long amount=PaymentAmounts.minor(input.amount());
    var prior=db.queryForList("SELECT * FROM staged_payment_requests WHERE request_key=?",input.requestKey());
    if (!prior.isEmpty()) {
      var p=prior.getFirst();
      if (!booking.equals(p.get("booking_id")) || !input.contractId().equals(p.get("contract_id"))
          || !input.kind().equals(p.get("kind")) || !Objects.equals(input.serviceKey(),p.get("service_key"))
          || amount!=((Number)p.get("amount_minor")).longValue()) throw DomainException.conflict("IDEMPOTENCY_KEY_REUSED");
      return list(booking);
    }
    if(db.queryForObject("SELECT count(*) FROM cancelled_services WHERE booking_id=?",Integer.class,booking)>0) throw DomainException.conflict("PAYMENT_PLAN_REVIEW_REQUIRED");
    if (!b.get("status").equals("PENDING_REVIEW") || ((Number)b.get("paid")).longValue()!=0)
      throw DomainException.conflict("PAYMENT_WORKFLOW_CONFLICT");
    if (db.queryForObject("SELECT count(*) FROM booking_requests WHERE booking_id=? AND status='PENDING'",Integer.class,booking)>0)
      throw DomainException.conflict("REQUEST_PENDING");
    var contract=db.queryForList("SELECT o.payload FROM contract_documents d JOIN offer_revisions o ON o.id=d.offer_id WHERE d.id=? AND d.booking_id=? AND d.state='ACCEPTED' AND o.source_quote_id=? AND o.revision=(SELECT max(revision) FROM offer_revisions WHERE booking_id=? AND source_quote_id=?)",input.contractId(),booking,b.get("quote_id"),booking,b.get("quote_id"));
    if (contract.isEmpty()) throw DomainException.conflict("CURRENT_CONTRACT_ACCEPTANCE_REQUIRED");
    var offer=json.readTree((String)contract.getFirst().get("payload"));
    long total=offer.path("total").asLong()*100;
    if (input.kind().equals("DEPOSIT") && input.serviceKey()!=null) throw DomainException.bad("INVALID_SERVICE_KEY");
    if (input.kind().equals("SERVICE")) {
      boolean found=false;
      for (var service:offer.path("services")) if (service.path("key").asText().equals(input.serviceKey())) found=true;
      if (!found) throw DomainException.bad("INVALID_SERVICE_KEY");
      if (db.queryForObject("SELECT count(*) FROM staged_payment_requests WHERE booking_id=? AND kind='DEPOSIT' AND status='PAID'",Integer.class,booking)==0)
        throw DomainException.conflict("DEPOSIT_REQUIRED");
    }
    long allocated=db.queryForObject("SELECT coalesce(sum(amount_minor),0) FROM staged_payment_requests WHERE booking_id=? AND status<>'CANCELLED'",Long.class,booking);
    PaymentAmounts.within(amount,allocated,total);
    if (input.kind().equals("DEPOSIT") && db.queryForObject("SELECT count(*) FROM staged_payment_requests WHERE booking_id=? AND kind='DEPOSIT' AND status<>'CANCELLED'",Integer.class,booking)>0)
      throw DomainException.conflict("DEPOSIT_ALREADY_REQUESTED");
    UUID id=UUID.randomUUID();
    db.update("INSERT INTO staged_payment_requests(id,booking_id,contract_id,kind,service_key,amount_minor,currency,request_key,created_by) VALUES(?,?,?,?,?,?,?,?,?)",id,booking,input.contractId(),input.kind(),input.serviceKey(),amount,offer.path("currency").asText(),input.requestKey(),actor);
    db.update("INSERT INTO booking_audit(id,booking_id,actor,action,note) VALUES(?,?,?,'PAYMENT_REQUEST_ISSUED',?)",UUID.randomUUID(),booking,actor,id.toString());
    return list(booking);
  }
}
