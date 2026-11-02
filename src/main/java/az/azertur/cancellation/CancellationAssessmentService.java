package az.azertur.cancellation;

import az.azertur.shared.DomainException;
import az.azertur.cancellation.CancellationCalculator.Tier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class CancellationAssessmentService {
  public record Line(@NotBlank @Size(max=30) String serviceKey,
      @NotBlank @Size(max=200) String supplierReference,@NotBlank @Size(max=4000) String policyText,
      @NotBlank @Size(max=2000) String basis,@Min(0) long allocatedPaidMinor,
      @NotEmpty @Size(max=20) List<@Valid @NotNull Tier> tiers) {}
  public record Assessment(@Min(0) long version,@NotNull UUID contractId,
      @NotEmpty @Size(max=5) List<@Valid @NotNull Line> lines) {}
  private final JdbcTemplate db; private final ObjectMapper json;
  private final az.azertur.booking.BookingService bookings;
  public CancellationAssessmentService(JdbcTemplate db,ObjectMapper json,az.azertur.booking.BookingService bookings) { this.db=db;this.json=json;this.bookings=bookings; }
  @Transactional
  public Object approve(UUID request,long version,String actor) {
    var r=db.queryForList("SELECT booking_id FROM booking_requests WHERE id=?",request).stream().findFirst().orElseThrow(DomainException::missing);
    UUID booking=(UUID)r.get("booking_id");
    db.queryForList("SELECT id FROM bookings WHERE id=? FOR UPDATE",booking);
    var rows=db.queryForList("SELECT a.*,r.status AS request_status FROM cancellation_assessments a JOIN booking_requests r ON r.id=a.request_id WHERE a.request_id=?",request);
    if(rows.isEmpty()) throw DomainException.conflict("CANCELLATION_REVIEW_REQUIRED");
    var a=rows.getFirst();
    if(((Number)a.get("version")).longValue()!=version) throw DomainException.conflict("STALE_ASSESSMENT");
    if(a.get("state").equals("APPROVED") && a.get("request_status").equals("ACCEPTED")) return get(request);
    if(!a.get("state").equals("REVIEWED") || !a.get("request_status").equals("PENDING")) throw DomainException.conflict("CANCELLATION_NOT_PENDING");
    db.update("UPDATE cancellation_assessments SET state='APPROVED',approved_at=now() WHERE request_id=?",request);
    bookings.resolve(request,true,"Cancellation reviewed; refund plan approved. No refund has been transferred.",actor,null);
    db.update("UPDATE staged_payment_requests SET status='CANCELLED' WHERE booking_id=? AND status='OPEN'",booking);
    return get(request);
  }
  public Object get(UUID request) {
    return db.queryForList("SELECT version,payload,state,reviewed_at,approved_at FROM cancellation_assessments WHERE request_id=?",request)
        .stream().findFirst().orElse(null);
  }
  @Transactional
  public Object review(UUID request,Assessment input,String actor) {
    var r=db.queryForList("SELECT * FROM booking_requests WHERE id=?",request).stream().findFirst().orElseThrow(DomainException::missing);
    UUID booking=(UUID)r.get("booking_id");
    var b=db.queryForList("SELECT * FROM bookings WHERE id=? FOR UPDATE",booking).getFirst();
    r=db.queryForList("SELECT * FROM booking_requests WHERE id=?",request).getFirst();
    if(!"CANCEL".equals(r.get("kind")) || !"PENDING".equals(r.get("status"))) throw DomainException.conflict("CANCELLATION_NOT_PENDING");
    var c=db.queryForList("SELECT d.id,o.payload FROM contract_documents d JOIN offer_revisions o ON o.id=d.offer_id WHERE d.booking_id=? AND d.accepted_at IS NOT NULL AND o.source_quote_id=? ORDER BY d.accepted_at DESC LIMIT 1",booking,b.get("quote_id"));
    if(c.isEmpty() || !input.contractId().equals(c.getFirst().get("id"))) throw DomainException.conflict("ACCEPTED_CONTRACT_REQUIRED");
    var offer=json.readTree((String)c.getFirst().get("payload"));
    long paid=((Number)b.get("paid")).longValue()*100;
    paid+=db.queryForObject("SELECT coalesce(sum(amount_minor),0) FROM staged_payment_requests WHERE booking_id=? AND status='PAID'",Long.class,booking);
    var stored=db.queryForList("SELECT version FROM cancellation_assessments WHERE request_id=?",request);
    long version=stored.isEmpty()?0:((Number)stored.getFirst().get("version")).longValue();
    if(version!=input.version()) throw DomainException.conflict("STALE_ASSESSMENT");
    var keys=new HashSet<String>(); long allocated=0,refund=0;
    var results=new ArrayList<Object>();
    Instant requested=((java.sql.Timestamp)r.get("created_at")).toInstant();
    for(var line:input.lines()) {
      if(!keys.add(line.serviceKey())) throw DomainException.bad("DUPLICATE_SERVICE");
      tools.jackson.databind.JsonNode service=null;
      for(var s:offer.path("services")) if(s.path("key").asText().equals(line.serviceKey())) service=s;
      if(service==null || !line.policyText().equals(service.path("policy").asText())) throw DomainException.conflict("POLICY_SOURCE_MISMATCH");
      if(line.allocatedPaidMinor()>service.path("total").asLong()*100) throw DomainException.bad("INVALID_ALLOCATION");
      var result=CancellationCalculator.calculate(line.allocatedPaidMinor(),service.path("total").asLong()*100,requested,line.tiers());
      allocated=Math.addExact(allocated,line.allocatedPaidMinor()); refund=Math.addExact(refund,result.refundMinor());
      results.add(Map.of("supplierReference",line.supplierReference(),"serviceKey",line.serviceKey(),"policyText",line.policyText(),"basis",line.basis(),"rules",line.tiers(),"calculation",result));
    }
    if(keys.size()!=offer.path("services").size() || allocated!=paid) throw DomainException.conflict("PAYMENT_ALLOCATION_REQUIRED");
    var payload=Map.of("contractId",input.contractId(),"requestedAt",requested,"currency",offer.path("currency").asText(),
        "paidMinor",paid,"refundMinor",refund,"retainedMinor",paid-refund,"lines",results);
    db.update("INSERT INTO cancellation_assessments(request_id,version,contract_id,source_quote_id,paid_minor,payload,state,reviewed_by) VALUES(?,?,?,?,?,?,'REVIEWED',?) ON CONFLICT(request_id) DO UPDATE SET version=excluded.version,contract_id=excluded.contract_id,source_quote_id=excluded.source_quote_id,paid_minor=excluded.paid_minor,payload=excluded.payload,state='REVIEWED',reviewed_by=excluded.reviewed_by,reviewed_at=now(),approved_at=null",request,version+1,input.contractId(),b.get("quote_id"),paid,json.writeValueAsString(payload),actor);
    db.update("INSERT INTO booking_audit(id,booking_id,actor,action,note) VALUES(?,?,?,'CANCELLATION_REVIEWED',?)",UUID.randomUUID(),booking,actor,request.toString());
    return get(request);
  }
}
