package az.azertur.payment;
import az.azertur.cancellation.CancellationPortal;
import az.azertur.customer.CustomerAuthService.Customer;
import az.azertur.shared.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
@Service
public class OfficeFinance {
 private final JdbcTemplate db;private final ObjectMapper json;private final CancellationPortal portal;private final boolean email;
 public OfficeFinance(JdbcTemplate db,ObjectMapper json,CancellationPortal portal,@Value("${app.notifications.email-enabled:false}") boolean email){this.db=db;this.json=json;this.portal=portal;this.email=email;}
 private void lock(UUID id){if(db.queryForList("SELECT id FROM bookings WHERE id=? FOR UPDATE",id).isEmpty())throw DomainException.missing();}
 private int count(UUID id){return db.queryForObject("SELECT count(*) FROM cancelled_services WHERE booking_id=?",Integer.class,id);}
 public Map<String,Object> context(UUID id){
  var result=new LinkedHashMap<String,Object>(portal.context(id));
  var allocations=db.queryForList("SELECT version,contract_id,paid_minor,payload,cancellation_count,updated_at FROM payment_allocations WHERE booking_id=?",id);
  result.put("allocation",allocations.isEmpty()?null:allocations.getFirst());
  result.put("allocationCurrent",!allocations.isEmpty()&&Objects.equals(result.get("contractId"),allocations.getFirst().get("contract_id"))&&((Number)allocations.getFirst().get("paid_minor")).longValue()==((Number)result.get("allocatableMinor")).longValue()&&((Number)allocations.getFirst().get("cancellation_count")).intValue()==count(id));
  result.put("plan",plan(id));result.put("cancellationCount",count(id));return result;
 }
 public record Line(@NotBlank @Size(max=30) String serviceKey,@Min(0) long amountMinor){}
 public record Allocation(@Min(0) long version,@NotNull UUID contractId,@Min(0) long expectedPaidMinor,@NotEmpty @Size(max=5) List<@Valid @NotNull Line> lines){}
 @Transactional public Object allocate(UUID id,Allocation a,String actor){
  lock(id);var c=context(id);if(c.get("contractId")==null||!a.contractId().equals(c.get("contractId")))throw DomainException.conflict("ACCEPTED_CONTRACT_REQUIRED");
  long paid=((Number)c.get("allocatableMinor")).longValue();if(paid!=a.expectedPaidMinor())throw DomainException.conflict("PAID_AMOUNT_CHANGED");
  var caps=new HashMap<String,Long>();for(Object s:(List<?>)c.get("services")){var n=(JsonNode)s;caps.put(n.path("key").asText(),n.path("total").decimalValue().movePointRight(2).longValueExact());}
  var keys=new HashSet<String>();long sum=0;for(var l:a.lines()){if(!keys.add(l.serviceKey())||!caps.containsKey(l.serviceKey())||l.amountMinor()>caps.get(l.serviceKey()))throw DomainException.bad("INVALID_ALLOCATION");sum=Math.addExact(sum,l.amountMinor());}
  if(!keys.equals(caps.keySet())||sum!=paid)throw DomainException.conflict("PAYMENT_ALLOCATION_REQUIRED");
  var old=db.queryForList("SELECT version FROM payment_allocations WHERE booking_id=?",id);long version=old.isEmpty()?0:((Number)old.getFirst().get("version")).longValue();if(version!=a.version())throw DomainException.conflict("STALE_ALLOCATION");
  db.update("INSERT INTO payment_allocations(booking_id,contract_id,version,paid_minor,payload,cancellation_count,updated_by) VALUES(?,?,?,?,?,?,?) ON CONFLICT(booking_id) DO UPDATE SET contract_id=excluded.contract_id,version=excluded.version,paid_minor=excluded.paid_minor,payload=excluded.payload,cancellation_count=excluded.cancellation_count,updated_by=excluded.updated_by,updated_at=now()",id,a.contractId(),version+1,paid,json.writeValueAsString(a.lines()),count(id),actor);
  db.update("UPDATE revised_payment_plans SET state='SUPERSEDED' WHERE booking_id=? AND state<>'SUPERSEDED'",id);audit(id,actor,"PAYMENT_ALLOCATED");return context(id);
 }
 public Object plan(UUID id){return db.queryForList("SELECT id,version,payload,state,created_at,accepted_at FROM revised_payment_plans WHERE booking_id=? ORDER BY version DESC LIMIT 1",id).stream().findFirst().orElse(null);}
 public record PlanInput(@Min(1) long allocationVersion){}
 @Transactional public Object sendPlan(UUID id,PlanInput input,String actor){
  lock(id);var c=context(id);if(!Boolean.TRUE.equals(c.get("allocationCurrent")))throw DomainException.conflict("CURRENT_ALLOCATION_REQUIRED");
  if(count(id)==0||List.of("CANCELLED","REJECTED").contains(c.get("bookingStatus")))throw DomainException.conflict("PARTIAL_CANCELLATION_REQUIRED");
  if(db.queryForObject("SELECT count(*) FROM booking_requests WHERE booking_id=? AND status='PENDING'",Integer.class,id)>0)throw DomainException.conflict("REQUEST_PENDING");
  var a=(Map<?,?>)c.get("allocation");if(((Number)a.get("version")).longValue()!=input.allocationVersion())throw DomainException.conflict("STALE_ALLOCATION");
  var prior=(Map<?,?>)plan(id);if(prior!=null&&!prior.get("state").equals("SUPERSEDED")&&json.readTree((String)prior.get("payload")).path("allocationVersion").asLong()==input.allocationVersion())return prior;
  long total=0;for(Object s:(List<?>)c.get("services"))total=Math.addExact(total,((JsonNode)s).path("total").decimalValue().movePointRight(2).longValueExact());long paid=((Number)c.get("allocatableMinor")).longValue();if(paid>total)throw DomainException.conflict("REFUND_REVIEW_REQUIRED");
  UUID plan=UUID.randomUUID();long version=db.queryForObject("SELECT coalesce(max(version),0)+1 FROM revised_payment_plans WHERE booking_id=?",Long.class,id);
  var payload=Map.of("contractId",c.get("contractId"),"services",c.get("services"),"currency",((JsonNode)c.get("offer")).path("currency").asText(),"totalMinor",total,"paidMinor",paid,"remainingMinor",total-paid,"allocationVersion",input.allocationVersion(),"cancellationCount",count(id));
  db.update("UPDATE revised_payment_plans SET state='SUPERSEDED' WHERE booking_id=? AND state<>'SUPERSEDED'",id);
  db.update("UPDATE staged_payment_requests SET status='CANCELLED' WHERE booking_id=? AND status='OPEN'",id);
  db.update("INSERT INTO revised_payment_plans(id,booking_id,contract_id,version,payload,cancellation_count,state,created_by) VALUES(?,?,?,?,?,?,'SENT',?)",plan,id,c.get("contractId"),version,json.writeValueAsString(payload),count(id),actor);audit(id,actor,"REVISED_PLAN_SENT");notify(id,"REVISED_PLAN_SENT");return plan(id);
 }
 public boolean planCurrent(UUID id){
  var p=(Map<?,?>)plan(id);if(p==null||!p.get("state").equals("ACCEPTED"))return false;
  var payload=json.readTree((String)p.get("payload"));var c=contextWithoutPlan(id);
  return payload.path("cancellationCount").asInt()==count(id)&&payload.path("paidMinor").asLong()==((Number)c.get("allocatableMinor")).longValue()&&payload.path("contractId").asText().equals(String.valueOf(c.get("contractId")));
 }
 private Map<String,Object> contextWithoutPlan(UUID id){return portal.context(id);}
 public Object customerPlan(UUID id,UUID customer){owned(id,customer);return plan(id);}
 public record AcceptPlan(@NotNull UUID planId,@Min(1) long version){}
 @Transactional public Object accept(UUID id,UUID customer,AcceptPlan a){
  lock(id);owned(id,customer);var p=(Map<?,?>)plan(id);if(p==null||!a.planId().equals(p.get("id"))||((Number)p.get("version")).longValue()!=a.version()||p.get("state").equals("SUPERSEDED"))throw DomainException.conflict("STALE_PLAN");
  var c=context(id);var payload=json.readTree((String)p.get("payload"));if(!Boolean.TRUE.equals(c.get("allocationCurrent"))||count(id)!=payload.path("cancellationCount").asInt()||((Number)c.get("allocatableMinor")).longValue()!=payload.path("paidMinor").asLong())throw DomainException.conflict("STALE_PLAN");
  if(db.queryForObject("SELECT count(*) FROM booking_requests WHERE booking_id=? AND status='PENDING'",Integer.class,id)>0)throw DomainException.conflict("REQUEST_PENDING");
  if(p.get("state").equals("ACCEPTED"))return p;
  db.update("UPDATE revised_payment_plans SET state='ACCEPTED',accepted_at=now(),accepted_by=? WHERE id=? AND state='SENT'",customer,a.planId());audit(id,customer.toString(),"REVISED_PLAN_ACCEPTED");return plan(id);
 }
 private void owned(UUID id,UUID customer){if(db.queryForObject("SELECT count(*) FROM bookings WHERE id=? AND customer_id=?",Integer.class,id,customer)==0)throw DomainException.missing();}
 private void audit(UUID id,String actor,String action){db.update("INSERT INTO booking_audit(id,booking_id,actor,action,note) VALUES(?,?,?,?,?)",UUID.randomUUID(),id,actor,action,"");}
 private void notify(UUID id,String event){db.update("INSERT INTO notification_outbox(id,customer_id,booking_id,channel,event,body,status) SELECT ?,customer_id,id,'EMAIL',?,'Review the update in your account.',? FROM bookings WHERE id=?",UUID.randomUUID(),event,email?"QUEUED":"WAITING_CONFIGURATION",id);db.update("INSERT INTO notification_outbox(id,customer_id,booking_id,channel,event,body,status) SELECT ?,customer_id,id,'PUSH',?,'Review the update in your account.','WAITING_CONFIGURATION' FROM bookings WHERE id=?",UUID.randomUUID(),event,id);}
 public Object refunds(){return db.queryForList("""
 SELECT source.request_id,source.booking_id,source.payload,source.kind,t.version,t.state,t.external_reference,t.customer_message,t.updated_at
 FROM (
 SELECT a.request_id,r.booking_id,a.payload,r.kind,a.approved_at AS occurred FROM cancellation_assessments a JOIN booking_requests r ON r.id=a.request_id WHERE a.state='APPROVED' AND (a.payload::jsonb->>'refundMinor')::bigint>0
 UNION ALL SELECT a.request_id,a.booking_id,a.payload,r.kind,a.created_at AS occurred FROM amendment_settlements a JOIN booking_requests r ON r.id=a.request_id WHERE a.refund_minor>0
 ) source LEFT JOIN refund_tracking t ON t.request_id=source.request_id ORDER BY source.occurred DESC
 """);}

 public record Refund(@Min(0) long version,@NotNull @Pattern(regexp="PROCESSING|COMPLETED_MANUAL|FAILED|UNKNOWN") String state,@NotNull @Size(max=200) String reference,@NotNull @Size(max=2000) String customerMessage,boolean manualTransferVerified){}
 @Transactional public Object refund(UUID request,Refund body,String actor){
  var rows=db.queryForList("SELECT r.booking_id,a.payload,r.kind FROM cancellation_assessments a JOIN booking_requests r ON r.id=a.request_id WHERE a.request_id=? AND a.state='APPROVED' UNION ALL SELECT a.booking_id,a.payload,r.kind FROM amendment_settlements a JOIN booking_requests r ON r.id=a.request_id WHERE a.request_id=?",request,request);if(rows.isEmpty())throw DomainException.missing();UUID booking=(UUID)rows.getFirst().get("booking_id");lock(booking);
  if(json.readTree((String)rows.getFirst().get("payload")).path("refundMinor").asLong()<=0)throw DomainException.conflict("NO_REFUND_REQUIRED");
  var old=db.queryForList("SELECT * FROM refund_tracking WHERE request_id=? FOR UPDATE",request);String state=old.isEmpty()?"PENDING":(String)old.getFirst().get("state");long v=old.isEmpty()?0:((Number)old.getFirst().get("version")).longValue();if(v!=body.version())throw DomainException.conflict("STALE_REFUND");
  boolean valid=switch(state){case "PENDING","FAILED" -> Set.of("PROCESSING","UNKNOWN").contains(body.state());case "PROCESSING","UNKNOWN" -> Set.of("COMPLETED_MANUAL","FAILED","UNKNOWN").contains(body.state());default -> false;};
  if(!valid)throw DomainException.conflict("INVALID_REFUND_TRANSITION");if(body.state().equals("COMPLETED_MANUAL")&&(!body.manualTransferVerified()||body.reference().trim().length()<6))throw DomainException.bad("TRANSFER_EVIDENCE_REQUIRED");
  db.update("INSERT INTO refund_tracking(request_id,version,state,external_reference,customer_message,updated_by) VALUES(?,?,?,?,?,?) ON CONFLICT(request_id) DO UPDATE SET version=excluded.version,state=excluded.state,external_reference=excluded.external_reference,customer_message=excluded.customer_message,updated_by=excluded.updated_by,updated_at=now()",request,v+1,body.state(),body.reference(),body.customerMessage(),actor);audit(booking,actor,"REFUND_"+body.state());notify(booking,rows.getFirst().get("kind").equals("CHANGE")?"AMENDMENT_REFUND_UPDATED":"REFUND_UPDATED");return refunds();
 }
}
@RestController class OfficeFinanceController {
 private final OfficeFinance finance;OfficeFinanceController(OfficeFinance finance){this.finance=finance;}
 @GetMapping("/api/v1/operations/bookings/{id}/allocation") Object allocation(@PathVariable UUID id){return finance.context(id);}
 @PostMapping("/api/v1/operations/bookings/{id}/allocation") Object allocation(@PathVariable UUID id,@Valid @RequestBody OfficeFinance.Allocation a,Principal p){return finance.allocate(id,a,p.getName());}
 @PostMapping("/api/v1/operations/bookings/{id}/revised-plan") Object plan(@PathVariable UUID id,@Valid @RequestBody OfficeFinance.PlanInput a,Principal p){return finance.sendPlan(id,a,p.getName());}
 @GetMapping("/api/v1/bookings/{id}/revised-plan") Object plan(@PathVariable UUID id,@AuthenticationPrincipal Customer c){return finance.customerPlan(id,c.id());}
 @PostMapping("/api/v1/bookings/{id}/revised-plan") Object accept(@PathVariable UUID id,@AuthenticationPrincipal Customer c,@Valid @RequestBody OfficeFinance.AcceptPlan a){return finance.accept(id,c.id(),a);}
 @GetMapping("/api/v1/operations/refunds") Object refunds(){return finance.refunds();}
 @PostMapping("/api/v1/operations/refunds/{id}") Object refund(@PathVariable UUID id,@Valid @RequestBody OfficeFinance.Refund a,Principal p){return finance.refund(id,a,p.getName());}
}
