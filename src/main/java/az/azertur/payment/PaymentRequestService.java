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
  private final OfficeFinance finance;
  private final boolean email;
  public PaymentRequestService(JdbcTemplate db,ObjectMapper json,OfficeFinance finance,@org.springframework.beans.factory.annotation.Value("${app.notifications.email-enabled:false}") boolean email) { this.email=email;this.db=db; this.json=json; this.finance=finance; }

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
    var cancelled=db.queryForList("SELECT service_key FROM cancelled_services WHERE booking_id=?",booking).stream().map(x->(String)x.get("service_key")).toList();
    if(!cancelled.isEmpty()&&!finance.planCurrent(booking)) throw DomainException.conflict("PAYMENT_PLAN_REVIEW_REQUIRED");
    if (!Set.of("PENDING_REVIEW","CONFIRMED").contains(b.get("status")))
      throw DomainException.conflict("PAYMENT_WORKFLOW_CONFLICT");
    if (db.queryForObject("SELECT count(*) FROM booking_requests WHERE booking_id=? AND status='PENDING'",Integer.class,booking)>0)
      throw DomainException.conflict("REQUEST_PENDING");
    var contract=db.queryForList("SELECT o.payload FROM contract_documents d JOIN offer_revisions o ON o.id=d.offer_id WHERE d.id=? AND d.booking_id=? AND d.state='ACCEPTED' AND o.source_quote_id=? AND o.revision=(SELECT max(revision) FROM offer_revisions WHERE booking_id=? AND source_quote_id=?)",input.contractId(),booking,b.get("quote_id"),booking,b.get("quote_id"));
    if (contract.isEmpty()) throw DomainException.conflict("CURRENT_CONTRACT_ACCEPTANCE_REQUIRED");
    var offer=json.readTree((String)contract.getFirst().get("payload"));
    long total=0;for(var line:offer.path("services"))if(!cancelled.contains(line.path("key").asText()))total=Math.addExact(total,line.path("total").decimalValue().movePointRight(2).longValueExact());
    if (input.kind().equals("DEPOSIT") && input.serviceKey()!=null) throw DomainException.bad("INVALID_SERVICE_KEY");
    if (input.kind().equals("SERVICE")) {
      boolean found=false;
      for (var service:offer.path("services")) if (service.path("key").asText().equals(input.serviceKey())) found=true;
      if (!found || cancelled.contains(input.serviceKey())) throw DomainException.bad("INVALID_SERVICE_KEY");
      if (db.queryForObject("SELECT count(*) FROM staged_payment_requests WHERE booking_id=? AND kind='DEPOSIT' AND status='PAID'",Integer.class,booking)==0)
        throw DomainException.conflict("DEPOSIT_REQUIRED");
    }
    long received=((Number)b.get("paid")).longValue()*100+db.queryForObject("SELECT coalesce(sum(amount_minor),0) FROM staged_payment_requests WHERE booking_id=? AND status='PAID'",Long.class,booking)-db.queryForObject("SELECT coalesce(sum(allocated_minor),0) FROM cancelled_services WHERE booking_id=?",Long.class,booking);
    long allocated=received+db.queryForObject("SELECT coalesce(sum(amount_minor),0) FROM staged_payment_requests WHERE booking_id=? AND status='OPEN'",Long.class,booking);
    if(input.kind().equals("SERVICE")){
      var allocation=finance.context(booking);if(!Boolean.TRUE.equals(allocation.get("allocationCurrent")))throw DomainException.conflict("CURRENT_ALLOCATION_REQUIRED");
      long share=0,cap=0;for(var line:json.readTree((String)((Map<?,?>)allocation.get("allocation")).get("payload")))if(line.path("serviceKey").asText().equals(input.serviceKey()))share=line.path("amountMinor").asLong();
      for(var line:offer.path("services"))if(line.path("key").asText().equals(input.serviceKey()))cap=line.path("total").decimalValue().movePointRight(2).longValueExact();
      long open=db.queryForObject("SELECT coalesce(sum(amount_minor),0) FROM staged_payment_requests WHERE booking_id=? AND service_key=? AND status='OPEN'",Long.class,booking,input.serviceKey());
      if(amount>cap-share-open)throw DomainException.conflict("SERVICE_AMOUNT_EXCEEDED");
    }
    if(input.kind().equals("DEPOSIT")&&received>0)throw DomainException.conflict("DEPOSIT_ALREADY_RECEIVED");
    PaymentAmounts.within(amount,allocated,total);
    if (input.kind().equals("DEPOSIT") && db.queryForObject("SELECT count(*) FROM staged_payment_requests WHERE booking_id=? AND kind='DEPOSIT' AND status<>'CANCELLED'",Integer.class,booking)>0)
      throw DomainException.conflict("DEPOSIT_ALREADY_REQUESTED");
    UUID id=UUID.randomUUID();
    db.update("INSERT INTO staged_payment_requests(id,booking_id,contract_id,kind,service_key,amount_minor,currency,request_key,created_by) VALUES(?,?,?,?,?,?,?,?,?)",id,booking,input.contractId(),input.kind(),input.serviceKey(),amount,offer.path("currency").asText(),input.requestKey(),actor);
    db.update("INSERT INTO booking_audit(id,booking_id,actor,action,note) VALUES(?,?,?,'PAYMENT_REQUEST_ISSUED',?)",UUID.randomUUID(),booking,actor,id.toString());
    for(String channel:List.of("EMAIL","PUSH"))db.update("INSERT INTO notification_outbox(id,customer_id,booking_id,channel,event,body,status) VALUES(?,?,?,?,'PAYMENT_REQUESTED','Review the payment request in your account.',?)",UUID.randomUUID(),b.get("customer_id"),booking,channel,channel.equals("EMAIL")&&email?"QUEUED":"WAITING_CONFIGURATION");
    return list(booking);
  }
}
