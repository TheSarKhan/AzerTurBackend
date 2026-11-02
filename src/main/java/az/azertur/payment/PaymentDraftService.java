package az.azertur.payment;

import az.azertur.shared.DomainException;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class PaymentDraftService {
 public record Draft(@Min(0) long version,@NotNull UUID contractId,@NotNull UUID requestKey,
   @NotNull @Pattern(regexp="DEPOSIT|SERVICE") String kind,@Size(max=30) String serviceKey,
   @NotNull BigDecimal amount,@NotNull @Size(max=2000) String message) {}
 public record Version(@Min(1) long version) {}
 private final JdbcTemplate db;private final ObjectMapper json;private final PaymentRequestService requests;private final boolean email;
 public PaymentDraftService(JdbcTemplate db,ObjectMapper json,PaymentRequestService requests,@Value("${app.notifications.email-enabled:false}") boolean email){this.db=db;this.json=json;this.requests=requests;this.email=email;}
 private Map<String,Object> lock(UUID id){return db.queryForList("SELECT * FROM bookings WHERE id=? FOR UPDATE",id).stream().findFirst().orElseThrow(DomainException::missing);}
 public Object context(UUID id) {
  var b=db.queryForList("SELECT * FROM bookings WHERE id=?",id).stream().findFirst().orElseThrow(DomainException::missing);
  var c=db.queryForList("SELECT d.id,d.version,o.payload FROM contract_documents d JOIN offer_revisions o ON o.id=d.offer_id WHERE d.booking_id=? AND d.state='ACCEPTED' AND o.source_quote_id=? AND o.revision=(SELECT max(revision) FROM offer_revisions WHERE booking_id=? AND source_quote_id=?) ORDER BY d.accepted_at DESC LIMIT 1",id,b.get("quote_id"),id,b.get("quote_id"));
  var out=new LinkedHashMap<String,Object>();out.put("bookingId",id);out.put("contract",c.isEmpty()?null:Map.of("id",c.getFirst().get("id"),"version",c.getFirst().get("version")));out.put("offer",c.isEmpty()?null:json.readTree((String)c.getFirst().get("payload")));out.put("requests",requests.list(id));out.put("paidMinor",((Number)b.get("paid")).longValue()*100+db.queryForObject("SELECT coalesce(sum(amount_minor),0) FROM staged_payment_requests WHERE booking_id=? AND status='PAID'",Long.class,id));out.put("paymentBlocked",!b.get("status").equals("PENDING_REVIEW") || db.queryForObject("SELECT count(*) FROM cancelled_services WHERE booking_id=?",Integer.class,id)>0 || db.queryForObject("SELECT count(*) FROM booking_requests WHERE booking_id=? AND status='PENDING'",Integer.class,id)>0);return out;
 }
 public Object customerContext(UUID id,UUID customer){if(db.queryForObject("SELECT count(*) FROM bookings WHERE id=? AND customer_id=?",Integer.class,id,customer)==0)throw DomainException.missing();return context(id);}
 public Object draft(UUID id){return db.queryForList("SELECT version,payload,preview_version FROM payment_request_drafts WHERE booking_id=?",id).stream().findFirst().map(row->{var result=new LinkedHashMap<String,Object>(row);var d=json.readValue((String)row.get("payload"),Draft.class);result.put("sent",db.queryForObject("SELECT count(*) FROM staged_payment_requests WHERE booking_id=? AND request_key=?",Integer.class,id,d.requestKey())>0);return result;}).orElse(null);}
 @Transactional public Object save(UUID id,Draft d,String actor){
  lock(id);var c=(Map<?,?>)context(id);var contract=(Map<?,?>)c.get("contract");if(contract==null||!d.contractId().equals(contract.get("id")))throw DomainException.conflict("CURRENT_CONTRACT_ACCEPTANCE_REQUIRED");
  long amount=PaymentAmounts.minor(d.amount());var offer=(tools.jackson.databind.JsonNode)c.get("offer");
  if(amount>offer.path("total").asLong()*100)throw DomainException.bad("INVALID_AMOUNT");
  if(d.kind().equals("DEPOSIT")&&d.serviceKey()!=null)throw DomainException.bad("INVALID_SERVICE_KEY");
  if(d.kind().equals("SERVICE")){boolean found=false;for(var s:offer.path("services"))if(s.path("key").asText().equals(d.serviceKey()))found=true;if(!found)throw DomainException.bad("INVALID_SERVICE_KEY");}
  var rows=db.queryForList("SELECT version FROM payment_request_drafts WHERE booking_id=?",id);long current=rows.isEmpty()?0:((Number)rows.getFirst().get("version")).longValue();
  if(current!=d.version())throw DomainException.conflict("STALE_PAYMENT_DRAFT");
  var next=new Draft(current+1,d.contractId(),d.requestKey(),d.kind(),d.serviceKey(),d.amount(),d.message());
  db.update("INSERT INTO payment_request_drafts(booking_id,version,payload,updated_by) VALUES(?,?,?,?) ON CONFLICT(booking_id) DO UPDATE SET version=excluded.version,payload=excluded.payload,preview_version=0,updated_by=excluded.updated_by,updated_at=now()",id,next.version(),json.writeValueAsString(next),actor);return draft(id);
 }
 @Transactional public Object preview(UUID id,long version){lock(id);var row=db.queryForList("SELECT * FROM payment_request_drafts WHERE booking_id=?",id).stream().findFirst().orElseThrow(DomainException::missing);if(((Number)row.get("version")).longValue()!=version)throw DomainException.conflict("STALE_PAYMENT_DRAFT");db.update("UPDATE payment_request_drafts SET preview_version=? WHERE booking_id=?",version,id);return draft(id);}
 @Transactional public Object send(UUID id,long version,String actor){
  var b=lock(id);var row=db.queryForList("SELECT * FROM payment_request_drafts WHERE booking_id=?",id).stream().findFirst().orElseThrow(DomainException::missing);
  if(((Number)row.get("version")).longValue()!=version)throw DomainException.conflict("STALE_PAYMENT_DRAFT");
  if(((Number)row.get("preview_version")).longValue()!=version)throw DomainException.conflict("PREVIEW_REQUIRED");
  var d=json.readValue((String)row.get("payload"),Draft.class);
  boolean prior=db.queryForObject("SELECT count(*) FROM staged_payment_requests WHERE request_key=?",Integer.class,d.requestKey())>0;
  requests.issue(id,new PaymentRequestService.Issue(d.contractId(),d.requestKey(),d.kind(),d.serviceKey(),d.amount()),actor);
  if(!prior){db.update("UPDATE staged_payment_requests SET customer_message=? WHERE request_key=?",d.message(),d.requestKey());
   db.update("INSERT INTO notification_outbox(id,customer_id,booking_id,channel,event,body,status) VALUES(?,?,?,'EMAIL','PAYMENT_REQUESTED',?,?)",UUID.randomUUID(),b.get("customer_id"),id,"A payment request is ready in your account.",email?"QUEUED":"WAITING_CONFIGURATION");}
  return requests.list(id);
 }
}
