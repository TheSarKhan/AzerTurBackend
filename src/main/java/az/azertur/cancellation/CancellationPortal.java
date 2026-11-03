package az.azertur.cancellation;

import az.azertur.booking.BookingService;
import az.azertur.customer.CustomerAuthService.Customer;
import az.azertur.shared.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@Service
public class CancellationPortal {
 private final JdbcTemplate db; private final ObjectMapper json; private final BookingService bookings;
 public CancellationPortal(JdbcTemplate db,ObjectMapper json,BookingService bookings) { this.db=db;this.json=json;this.bookings=bookings; }
 public Map<String,Object> context(UUID id) {
  var b=bookings.get(id);
  var rows=db.queryForList("SELECT d.id,d.version,o.payload FROM contract_documents d JOIN offer_revisions o ON o.id=d.offer_id WHERE d.booking_id=? AND d.accepted_at IS NOT NULL AND o.source_quote_id=? ORDER BY d.accepted_at DESC LIMIT 1",id,b.quoteId());
  JsonNode offer;
  UUID contractId=null;
  if(rows.isEmpty()) {
   if(b.paid()>0) throw DomainException.conflict("ACCEPTED_CONTRACT_REQUIRED");
   var latest=db.queryForList("SELECT payload FROM offer_revisions WHERE booking_id=? AND source_quote_id=? ORDER BY revision DESC LIMIT 1",id,b.quoteId());
   if(!latest.isEmpty()) offer=json.readTree((String)latest.getFirst().get("payload"));
   else {
    var quote=json.readTree(b.quotePayload());
    var serviceList=new ArrayList<Object>();
    for(var line:quote.path("lines")) if(List.of("transport","hotel","guide","transfer","activity").contains(line.path("label").asText())) serviceList.add(Map.of("key",line.path("label").asText(),"name",line.path("label").asText(),"total",line.path("amount").asLong(),"policy",""));
    offer=json.valueToTree(Map.of("title",b.title(),"currency",b.currency(),"date",b.departure().toString(),"travelers",quote.path("input").path("pax").asInt(),"services",serviceList));
   }
  } else {contractId=(UUID)rows.getFirst().get("id");offer=json.readTree((String)rows.getFirst().get("payload"));}
  var cancelled=db.queryForList("SELECT service_key FROM cancelled_services WHERE booking_id=?",id).stream().map(r -> (String)r.get("service_key")).toList();
  var services=new ArrayList<JsonNode>();
  for(var s:offer.path("services")) if(!cancelled.contains(s.path("key").asText())) services.add(s);
  long paid=b.paid()*100L+db.queryForObject("SELECT coalesce(sum(amount_minor),0) FROM staged_payment_requests WHERE booking_id=? AND status='PAID'",Long.class,id);
  if(contractId==null && paid>0) throw DomainException.conflict("ACCEPTED_CONTRACT_REQUIRED");
  long allocated=db.queryForObject("SELECT coalesce(sum(allocated_minor),0) FROM cancelled_services WHERE booking_id=?",Long.class,id);
  var out=new LinkedHashMap<String,Object>();out.put("contractId",contractId);out.put("offer",offer);out.put("services",services);out.put("paidMinor",paid);out.put("allocatableMinor",paid-allocated);out.put("bookingStatus",b.status());out.put("contractVersion",rows.isEmpty()?0:rows.getFirst().get("version"));return out;
 }
 public Object customerContext(UUID id,UUID customer) {
  if(!bookings.get(id).customerId().equals(customer)) throw DomainException.missing();
  return context(id);
 }
 public Object operationsContext(UUID request) {
  var r=db.queryForList("SELECT * FROM booking_requests WHERE id=?",request).stream().findFirst().orElseThrow(DomainException::missing);
  var out=new LinkedHashMap<String,Object>(context((UUID)r.get("booking_id")));
  var allocation=db.queryForList("SELECT payload,contract_id,paid_minor,cancellation_count FROM payment_allocations WHERE booking_id=?",r.get("booking_id"));
  if(!allocation.isEmpty()&&Objects.equals(allocation.getFirst().get("contract_id"),out.get("contractId"))&&((Number)allocation.getFirst().get("paid_minor")).longValue()==((Number)out.get("allocatableMinor")).longValue())out.put("savedAllocation",json.readTree((String)allocation.getFirst().get("payload")));
  out.put("request",r);
  out.put("customer",db.queryForList("SELECT c.name,c.email,c.locale FROM customers c JOIN bookings b ON b.customer_id=c.id WHERE b.id=?",r.get("booking_id")).getFirst());
  var selection=db.queryForList("SELECT service_keys,reason FROM cancellation_selections WHERE request_id=?",request);
  out.put("serviceKeys",selection.isEmpty()?List.of():json.readTree((String)selection.getFirst().get("service_keys")));
  out.put("reason",selection.isEmpty()?"":selection.getFirst().get("reason"));
  return out;
 }
 public record Submit(UUID contractId,@NotEmpty @Size(max=5) List<@NotBlank String> serviceKeys,@NotBlank @Size(max=200) String reason,@NotNull @Size(max=1500) String message) {}
 @Transactional
 public Object submit(UUID id,Customer customer,Submit input) {
  var b=bookings.get(id);
  if(!b.customerId().equals(customer.id())) throw DomainException.missing();
  db.queryForList("SELECT id FROM bookings WHERE id=? FOR UPDATE",id);
  var c=context(id);
  if(!Objects.equals(input.contractId(),c.get("contractId"))) throw DomainException.conflict("STALE_CONTRACT");
  var allowed=new HashSet<String>();
  for(var s:(List<?>)c.get("services")) allowed.add(((JsonNode)s).path("key").asText());
  if(new HashSet<>(input.serviceKeys()).size()!=input.serviceKeys().size() || !allowed.containsAll(input.serviceKeys())) throw DomainException.bad("INVALID_SERVICE_SELECTION");
  if(c.get("contractId")==null && input.serviceKeys().size()!=allowed.size()) throw DomainException.conflict("CONTRACT_REQUIRED_FOR_PARTIAL_CANCELLATION");
  var r=bookings.request(id,customer.id(),"CANCEL",input.reason()+": "+input.message());
  db.update("INSERT INTO cancellation_selections(request_id,service_keys,reason) VALUES(?,?,?)",r.id(),json.writeValueAsString(input.serviceKeys()),input.reason());
  return r;
 }
 public Object updates(UUID id,UUID customer) {
  if(!bookings.get(id).customerId().equals(customer)) throw DomainException.missing();
  return db.queryForList("SELECT r.id,r.status,r.message,r.created_at,r.resolved_at,a.payload,a.state,a.approved_at,a.customer_explanation FROM booking_requests r LEFT JOIN cancellation_assessments a ON a.request_id=r.id AND a.state='APPROVED' WHERE r.booking_id=? AND r.kind='CANCEL' ORDER BY r.created_at DESC",id).stream().map(r -> {
   var out=new LinkedHashMap<String,Object>();
   for(var key:List.of("id","status","message","created_at","approved_at","customer_explanation")) out.put(key,r.get(key));
   if(r.get("approved_at")==null && r.get("resolved_at")!=null) out.put("approved_at",r.get("resolved_at"));
   if(r.get("payload")!=null) {
    var p=json.readTree((String)r.get("payload"));
    for(var key:List.of("currency","paidMinor","refundMinor","retainedMinor","unaffectedMinor")) out.put(key,p.path(key));
    var lines=new ArrayList<Object>();
    for(var l:p.path("lines")) if(!l.has("cancelled") || l.path("cancelled").asBoolean()) lines.add(Map.of("serviceKey",l.path("serviceKey"),"policyText",l.path("policyText"),"calculation",l.path("calculation")));
    out.put("lines",lines); out.put("refundStatus",p.path("refundMinor").asLong()>0?"PENDING":"NOT_REQUIRED");
    var tracking=db.queryForList("SELECT state,customer_message,updated_at FROM refund_tracking WHERE request_id=?",r.get("id"));
    if(!tracking.isEmpty()){out.put("refundStatus",tracking.getFirst().get("state"));out.put("refundMessage",tracking.getFirst().get("customer_message"));out.put("refundUpdatedAt",tracking.getFirst().get("updated_at"));}
   }
   if(r.get("payload")==null && "ACCEPTED".equals(r.get("status")) && bookings.get(id).paid()==0 && db.queryForObject("SELECT count(*) FROM staged_payment_requests WHERE booking_id=? AND status='PAID'",Integer.class,id)==0) {
    out.put("paidMinor",0);out.put("retainedMinor",0);out.put("refundMinor",0);out.put("unaffectedMinor",0);out.put("currency",bookings.get(id).currency());out.put("refundStatus","NOT_REQUIRED");
   }
   return out;
  }).toList();
 }
}
@RestController
class CancellationPortalController {
 private final CancellationPortal portal;
 CancellationPortalController(CancellationPortal portal) { this.portal=portal; }
 @GetMapping("/api/v1/bookings/{id}/cancellation") Object context(@PathVariable UUID id,@AuthenticationPrincipal Customer c) { return portal.customerContext(id,c.id()); }
 @PostMapping("/api/v1/bookings/{id}/cancellation") Object submit(@PathVariable UUID id,@AuthenticationPrincipal Customer c,@Valid @RequestBody CancellationPortal.Submit body) { return portal.submit(id,c,body); }
 @GetMapping("/api/v1/bookings/{id}/cancellation-updates") Object updates(@PathVariable UUID id,@AuthenticationPrincipal Customer c) { return portal.updates(id,c.id()); }
 @GetMapping("/api/v1/operations/requests/{id}/cancellation-context") Object context(@PathVariable UUID id) { return portal.operationsContext(id); }
}
