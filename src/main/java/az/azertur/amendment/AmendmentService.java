package az.azertur.amendment;

import az.azertur.cancellation.CancellationCalculator;
import az.azertur.offer.OfferService;
import az.azertur.offer.OfferService.Day;
import az.azertur.offer.OfferService.Line;
import az.azertur.cancellation.CancellationCalculator.Tier;
import az.azertur.payment.*;
import az.azertur.shared.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.*;

@Service
public class AmendmentService {
  public record PreviousTerm(@NotBlank @Size(max=30) String serviceKey,@NotNull @Size(max=4000) String policyText,
      @NotNull @Size(max=200) String supplierReference,@NotNull @Size(max=2000) String basis,
      @NotNull @Size(max=20) List<@NotNull @Valid Tier> tiers,boolean verified){}
  public record Draft(@Min(0) long version,@NotNull UUID replacementQuoteId,@NotBlank @Size(max=160) String title,
      @NotNull @Size(min=1,max=14) List<@Valid @NotNull Day> itinerary,
      @NotNull @Size(max=1500) String returnRoute,@NotNull @Size(min=1,max=5) List<@Valid @NotNull Line> services,
      @NotNull @Size(min=1,max=5) List<@Valid @NotNull PreviousTerm> previousTerms,
      @NotNull @Size(min=1,max=5) List<OfficeFinance.@Valid @NotNull Line> allocations,
      @NotNull @Size(max=2000) String message,@NotNull @Size(max=4000) String internalNote){}
  public record Version(@Min(1) long version,@NotBlank @Pattern(regexp="[0-9a-f]{64}") String sha256,boolean pdfReviewed){}
  public record Accept(@NotNull UUID amendmentId,@Min(1) long version,@NotBlank @Pattern(regexp="[0-9a-f]{64}") String sha256){}
  public record Decline(@NotNull UUID amendmentId,@Min(1) long version,@NotNull @Size(max=1000) String message){}
  private final JdbcTemplate db;private final ObjectMapper json;private final boolean email;
  public AmendmentService(JdbcTemplate db,ObjectMapper json,@Value("${app.notifications.email-enabled:false}") boolean email){this.db=db;this.json=json;this.email=email;}
  private Map<String,Object> request(UUID id){return db.queryForList("SELECT * FROM booking_requests WHERE id=? AND kind='CHANGE'",id).stream().findFirst().orElseThrow(DomainException::missing);}
  private Map<String,Object> lock(UUID request){var r=request(request);db.queryForList("SELECT id FROM bookings WHERE id=? FOR UPDATE",r.get("booking_id"));return request(request);}
  private String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
  private String hash(String value){return hash(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  private Map<String,Object> source(UUID request){
    var r=request(request);UUID booking=(UUID)r.get("booking_id");
    var b=db.queryForMap("SELECT * FROM bookings WHERE id=?",booking);
    var contracts=db.queryForList("SELECT c.id,c.version,c.sha256,o.payload FROM contract_documents c JOIN offer_revisions o ON o.id=c.offer_id WHERE c.booking_id=? AND c.state='ACCEPTED' AND o.source_quote_id=? ORDER BY c.accepted_at DESC LIMIT 1",booking,b.get("quote_id"));
    if(contracts.isEmpty())throw DomainException.conflict("ACCEPTED_CONTRACT_REQUIRED");
    var contract=contracts.getFirst();var offer=json.readTree((String)contract.get("payload"));
    var cancelled=db.queryForList("SELECT service_key FROM cancelled_services WHERE booking_id=? AND service_revision=?",booking,b.get("service_revision")).stream().map(x->(String)x.get("service_key")).toList();
    var active=new ArrayList<JsonNode>();for(var s:offer.path("services"))if(!cancelled.contains(s.path("key").asText()))active.add(s);
    long paid=PaymentFunds.active(db,booking);int cancellations=db.queryForObject("SELECT count(*) FROM cancelled_services WHERE booking_id=?",Integer.class,booking);
    var allocations=db.queryForList("SELECT * FROM payment_allocations WHERE booking_id=?",booking);
    boolean current=!allocations.isEmpty()&&Objects.equals(allocations.getFirst().get("contract_id"),contract.get("id"))&&((Number)allocations.getFirst().get("paid_minor")).longValue()==paid&&((Number)allocations.getFirst().get("cancellation_count")).intValue()==cancellations;
    long version=current?((Number)allocations.getFirst().get("version")).longValue():0;
    var signature=hash(contract.get("id")+":"+contract.get("sha256")+":"+b.get("quote_id")+":"+b.get("service_revision")+":"+paid+":"+cancellations+":"+version+":"+b.get("status"));
    var out=new LinkedHashMap<String,Object>();out.put("request",r);out.put("booking",b);out.put("contract",contract);out.put("offer",offer);out.put("services",active);out.put("paidMinor",paid);out.put("allocationCurrent",current||paid==0);out.put("allocationVersion",version);out.put("allocations",current?json.readTree((String)allocations.getFirst().get("payload")):List.of());out.put("signature",signature);return out;
  }
  public Object context(UUID id){
    var c=source(id);var b=(Map<?,?>)c.remove("booking");c.remove("signature");
    c.put("bookingId",b.get("id"));c.put("bookingStatus",b.get("status"));c.put("quotePayload",json.readTree((String)b.get("quote_payload")));
    c.put("customer",db.queryForMap("SELECT name,email,locale FROM customers WHERE id=?",b.get("customer_id")));
    c.put("draft",draft(id));c.put("versions",db.queryForList("SELECT id,draft_version,state,pdf_filename,pdf_sha256,sent_at,accepted_at,public_payload FROM amendment_versions WHERE request_id=? ORDER BY sent_at DESC",id));return c;
  }
  private void pending(Map<String,Object> c){
    var r=(Map<?,?>)c.get("request");var b=(Map<?,?>)c.get("booking");
    if(!r.get("status").equals("PENDING")||Set.of("CANCELLED","REJECTED").contains(b.get("status")))throw DomainException.conflict("AMENDMENT_NOT_PENDING");
    if(!Boolean.TRUE.equals(c.get("allocationCurrent")))throw DomainException.conflict("CURRENT_ALLOCATION_REQUIRED");
  }
  private Map<String,Object> quote(UUID id){return db.queryForList("SELECT q.*,t.days FROM quotes q JOIN tours t ON t.slug=q.tour_slug WHERE q.id=?",id).stream().findFirst().orElseThrow(DomainException::missing);}
  private List<String> route(Map<String,Object> q){
    var input=json.readTree((String)q.get("payload")).path("input");var result=new ArrayList<String>();
    if(input.path("customDays").isNumber())for(var city:input.path("cities"))result.add(city.asText());
    else result.addAll(switch((String)q.get("tour_slug")){case "quba-khinalug"->List.of("Quba","Khinalug");case "sheki-gabala"->List.of("Baku","Gabala","Sheki");default->List.of("Baku");});
    return result;
  }
  private void target(Draft d,Map<String,Object> c,boolean complete){
    var q=quote(d.replacementQuoteId());var b=(Map<?,?>)c.get("booking");
    if(q.get("payload")==null||((java.sql.Timestamp)q.get("expires_at")).toInstant().isBefore(Instant.now())||((java.sql.Date)q.get("departure")).toLocalDate().isBefore(LocalDate.now(ZoneId.of("Asia/Baku"))))throw DomainException.conflict("QUOTE_EXPIRED");
    if(PaymentFunds.received(db,(UUID)b.get("id"))>0&&!q.get("currency").equals(b.get("currency")))throw DomainException.conflict("PAID_CURRENCY_CANNOT_CHANGE");
    var cities=route(q);if(!d.itinerary().stream().map(OfferService.Day::city).toList().equals(cities))throw DomainException.bad("QUOTE_ROUTE_MISMATCH");
    var expected=new HashSet<String>();for(var line:json.readTree((String)q.get("payload")).path("lines"))expected.add(line.path("label").asText());
    var keys=new HashSet<String>();long total=0;
    var units=Map.of("transport","day","hotel","room","guide","day","transfer","transfer","activity","person");
    for(var s:d.services()){
      if(!keys.add(s.key())||!Objects.equals(units.get(s.key()),s.unit())||!s.key().equals("hotel")&&s.nights()!=1)throw DomainException.bad("INVALID_SERVICE_UNITS");
      long price=OfferService.amount(s);if(price<1)throw DomainException.bad("INVALID_SERVICE_PRICE");total=Math.addExact(total,price);
      if(complete&&(!s.availability().equals("CONFIRMED")||s.supplier().isBlank()||s.policy().isBlank()))throw DomainException.conflict("SERVICE_CHECKS_REQUIRED");
    }
    if(!keys.equals(expected)||total<1||total>10000000)throw DomainException.bad("INVALID_AMENDMENT_SERVICES");
    if(complete&&(d.message().isBlank()||d.returnRoute().isBlank()))throw DomainException.conflict("AMENDMENT_DETAILS_REQUIRED");
  }
  public Object draft(UUID request){return db.queryForList("SELECT version,payload,pdf_filename,pdf_sha256,preview_version,updated_at FROM amendment_drafts WHERE request_id=?",request).stream().findFirst().orElse(null);}
  @Transactional public Object save(UUID id,Draft d,String actor){
    lock(id);var c=source(id);pending(c);target(d,c,false);
    var old=db.queryForList("SELECT version FROM amendment_drafts WHERE request_id=?",id);long version=old.isEmpty()?0:((Number)old.getFirst().get("version")).longValue();if(version!=d.version())throw DomainException.conflict("STALE_AMENDMENT");
    var next=new Draft(version+1,d.replacementQuoteId(),d.title(),d.itinerary(),d.returnRoute(),d.services(),d.previousTerms(),d.allocations(),d.message(),d.internalNote());
    db.update("INSERT INTO amendment_drafts(request_id,version,payload,base_signature,updated_by) VALUES(?,?,?,?,?) ON CONFLICT(request_id) DO UPDATE SET version=excluded.version,payload=excluded.payload,base_signature=excluded.base_signature,pdf_content=null,pdf_filename=null,pdf_sha256=null,preview_version=0,updated_by=excluded.updated_by,updated_at=now()",id,next.version(),json.writeValueAsString(next),c.get("signature"),actor);
    audit((UUID)((Map<?,?>)c.get("booking")).get("id"),actor,"AMENDMENT_DRAFT_SAVED");return draft(id);
  }
  private Map<String,Object> stored(UUID id,long version){var d=db.queryForList("SELECT * FROM amendment_drafts WHERE request_id=?",id).stream().findFirst().orElseThrow(DomainException::missing);if(((Number)d.get("version")).longValue()!=version)throw DomainException.conflict("STALE_AMENDMENT");return d;}
  @Transactional public Object upload(UUID id,long version,MultipartFile file,String actor)throws Exception{
    lock(id);var c=source(id);pending(c);var d=stored(id,version);if(!c.get("signature").equals(d.get("base_signature")))throw DomainException.conflict("AMENDMENT_SOURCE_CHANGED");
    if(file.isEmpty()||file.getSize()>5000000)throw DomainException.bad("INVALID_PDF_SIZE");byte[] bytes=file.getBytes();
    if(bytes.length<8||!new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))throw DomainException.bad("INVALID_PDF");
    try(var pdf=Loader.loadPDF(bytes)){if(pdf.isEncrypted()||pdf.getNumberOfPages()<1||pdf.getNumberOfPages()>100)throw DomainException.bad("INVALID_PDF");}catch(java.io.IOException e){throw DomainException.bad("INVALID_PDF");}
    String name=Optional.ofNullable(file.getOriginalFilename()).orElse("amendment.pdf").replaceAll("[^a-zA-Z0-9._-]","_");if(name.length()>160)name=name.substring(name.length()-160);
    db.update("UPDATE amendment_drafts SET pdf_content=?,pdf_filename=?,pdf_sha256=?,preview_version=0 WHERE request_id=?",bytes,name,hash(bytes),id);audit((UUID)((Map<?,?>)c.get("booking")).get("id"),actor,"AMENDMENT_PDF_SAVED");return draft(id);
  }
  @Transactional public Object calculate(UUID id,Draft d){lock(id);var c=source(id);pending(c);return project(id,d,c,false);}
  private Map<String,Object> project(UUID request,Draft d,Map<String,Object> c){return project(request,d,c,true);}
  private Map<String,Object> project(UUID request,Draft d,Map<String,Object> c,boolean requireAllocation){
    target(d,c,true);long paid=((Number)c.get("paidMinor")).longValue();var q=quote(d.replacementQuoteId());
    var shares=new HashMap<String,Long>();for(var l:json.valueToTree(c.get("allocations")))shares.put(l.path("serviceKey").asText(),l.path("amountMinor").asLong());
    var oldServices=new HashMap<String,JsonNode>();for(Object s:(List<?>)c.get("services"))oldServices.put(((JsonNode)s).path("key").asText(),(JsonNode)s);
    var seen=new HashSet<String>();var fees=new ArrayList<Object>();long retained=0;Instant requested=((java.sql.Timestamp)((Map<?,?>)c.get("request")).get("created_at")).toInstant();
    for(var review:d.previousTerms()){
      var service=oldServices.get(review.serviceKey());if(service==null||!seen.add(review.serviceKey())||!review.policyText().equals(service.path("policy").asText()))throw DomainException.conflict("POLICY_SOURCE_MISMATCH");
      if(!review.verified()||review.supplierReference().isBlank()||review.basis().isBlank())throw DomainException.conflict("SUPPLIER_REVIEW_REQUIRED");
      long share=shares.getOrDefault(review.serviceKey(),0L);var fee=CancellationCalculator.calculate(share,service.path("total").decimalValue().movePointRight(2).longValueExact(),requested,review.tiers());retained=Math.addExact(retained,fee.retainedMinor());
      fees.add(Map.of("serviceKey",review.serviceKey(),"name",service.path("name").asText(),"policyText",review.policyText(),"allocatedPaidMinor",share,"retainedMinor",fee.retainedMinor(),"tierFrom",fee.tierFrom()));
    }
    if(!seen.equals(oldServices.keySet()))throw DomainException.conflict("SERVICE_POLICY_REVIEW_REQUIRED");
    List<OfferService.PublicLine> lines=d.services().stream().map(l->new OfferService.PublicLine(l.key(),l.name(),l.quantity(),l.nights(),l.unitPrice(),l.unit(),OfferService.amount(l),l.policy())).toList();
    int total=lines.stream().mapToInt(OfferService.PublicLine::total).sum();long refund=Math.max(0,paid-retained-total*100L),applied=paid-retained-refund;
    var allocationKeys=new HashSet<String>();var caps=new HashMap<String,Long>();for(var l:lines)caps.put(l.key(),l.total()*100L);long sum=0;
    for(var a:d.allocations()){if(!allocationKeys.add(a.serviceKey())||!caps.containsKey(a.serviceKey())||a.amountMinor()>caps.get(a.serviceKey()))throw DomainException.bad("INVALID_ALLOCATION");sum=Math.addExact(sum,a.amountMinor());}
    if(requireAllocation&&(!allocationKeys.equals(caps.keySet())||sum!=applied))throw DomainException.conflict("PAYMENT_ALLOCATION_REQUIRED");
    var out=new LinkedHashMap<String,Object>();out.put("requestId",request);out.put("title",d.title());out.put("currency",q.get("currency"));out.put("date",((java.sql.Date)q.get("departure")).toLocalDate().toString());out.put("travelers",json.readTree((String)q.get("payload")).path("input").path("pax").asInt());out.put("itinerary",d.itinerary());out.put("returnRoute",d.returnRoute());out.put("services",lines);out.put("message",d.message());out.put("previousTerms",fees);out.put("paidMinor",paid);out.put("retainedMinor",retained);out.put("refundMinor",refund);out.put("appliedMinor",applied);out.put("totalMinor",total*100L);out.put("remainingMinor",total*100L-applied);out.put("allocations",d.allocations());out.put("expiresAt",((java.sql.Timestamp)q.get("expires_at")).toInstant());return out;
  }
  @Transactional public Object preview(UUID id,Version version,String actor){
    lock(id);var c=source(id);pending(c);var stored=stored(id,version.version());checkDocument(stored,version);if(!c.get("signature").equals(stored.get("base_signature")))throw DomainException.conflict("AMENDMENT_SOURCE_CHANGED");
    var result=project(id,json.readValue((String)stored.get("payload"),Draft.class),c);db.update("UPDATE amendment_drafts SET preview_version=? WHERE request_id=?",version.version(),id);return result;
  }
  private void checkDocument(Map<String,Object> d,Version v){if(d.get("pdf_content")==null||!v.pdfReviewed()||!v.sha256().equals(d.get("pdf_sha256")))throw DomainException.conflict("REVIEWED_PDF_REQUIRED");}
  @Transactional public Object send(UUID id,Version v,String actor){
    lock(id);var prior=db.queryForList("SELECT * FROM amendment_versions WHERE request_id=? AND draft_version=?",id,v.version());if(!prior.isEmpty()){if(!v.sha256().equals(prior.getFirst().get("pdf_sha256")))throw DomainException.conflict("STALE_AMENDMENT");return summary(prior.getFirst());}
    var c=source(id);pending(c);var stored=stored(id,v.version());checkDocument(stored,v);if(((Number)stored.get("preview_version")).longValue()!=v.version())throw DomainException.conflict("PREVIEW_REQUIRED");
    if(!c.get("signature").equals(stored.get("base_signature")))throw DomainException.conflict("AMENDMENT_SOURCE_CHANGED");var draft=json.readValue((String)stored.get("payload"),Draft.class);var payload=project(id,draft,c);
    UUID amendment=UUID.randomUUID(),booking=(UUID)((Map<?,?>)c.get("booking")).get("id");db.update("UPDATE amendment_versions SET state='SUPERSEDED' WHERE request_id=? AND state='SENT'",id);
    db.update("INSERT INTO amendment_versions(id,request_id,booking_id,draft_version,base_contract_id,base_signature,target_quote_id,public_payload,private_payload,pdf_content,pdf_filename,pdf_sha256,state,sent_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'SENT',?)",amendment,id,booking,v.version(),((Map<?,?>)c.get("contract")).get("id"),c.get("signature"),draft.replacementQuoteId(),json.writeValueAsString(payload),stored.get("payload"),stored.get("pdf_content"),stored.get("pdf_filename"),stored.get("pdf_sha256"),actor);
    audit(booking,actor,"AMENDMENT_SENT");notify(booking,"AMENDMENT_SENT");return summary(version(amendment));
  }
  private Map<String,Object> version(UUID id){return db.queryForList("SELECT * FROM amendment_versions WHERE id=?",id).stream().findFirst().orElseThrow(DomainException::missing);}
  private Map<String,Object> summary(Map<String,Object> row){var out=new LinkedHashMap<String,Object>();for(String k:List.of("id","request_id","booking_id","draft_version","base_contract_id","state","pdf_filename","pdf_sha256","sent_at","accepted_at","applied_contract_id"))out.put(k,row.get(k));out.put("proposal",json.readTree((String)row.get("public_payload")));return out;}
  private void owned(UUID id,UUID customer){if(db.queryForObject("SELECT count(*) FROM bookings WHERE id=? AND customer_id=?",Integer.class,id,customer)==0)throw DomainException.missing();}
  public Object customer(UUID booking,UUID customer){
    owned(booking,customer);var out=new ArrayList<Object>();for(var row:db.queryForList("SELECT * FROM amendment_versions WHERE booking_id=? ORDER BY sent_at DESC",booking)){
      var view=summary(row);var r=request((UUID)row.get("request_id"));boolean current=r.get("status").equals("PENDING")&&row.get("state").equals("SENT");
      try{var c=source((UUID)row.get("request_id"));current=current&&row.get("base_signature").equals(c.get("signature"))&&Instant.parse(json.readTree((String)row.get("public_payload")).path("expiresAt").asText()).isAfter(Instant.now());}catch(DomainException e){current=false;}
      view.put("canAccept",current);view.put("requestStatus",r.get("status"));var refund=db.queryForList("SELECT state,customer_message,updated_at FROM refund_tracking WHERE request_id=?",row.get("request_id"));view.put("refundTracking",refund.isEmpty()?null:refund.getFirst());out.add(view);
    }return out;
  }
  public byte[] pdf(UUID booking,UUID id,UUID customer,boolean base){owned(booking,customer);var row=version(id);if(!booking.equals(row.get("booking_id")))throw DomainException.missing();return base?db.queryForObject("SELECT content FROM contract_documents WHERE id=? AND booking_id=?",byte[].class,row.get("base_contract_id"),booking):(byte[])row.get("pdf_content");}
  public byte[] operatorPdf(UUID request,Long draftVersion,UUID published){
    if(published!=null){var row=version(published);if(!request.equals(row.get("request_id")))throw DomainException.missing();return (byte[])row.get("pdf_content");}
    return (byte[])stored(request,Objects.requireNonNull(draftVersion)).get("pdf_content");
  }
  @Transactional public Object accept(UUID booking,UUID customer,Accept input){
    owned(booking,customer);var candidate=version(input.amendmentId());if(!booking.equals(candidate.get("booking_id")))throw DomainException.missing();UUID request=(UUID)candidate.get("request_id");lock(request);var row=version(input.amendmentId());
    if(!input.sha256().equals(row.get("pdf_sha256"))||input.version()!=((Number)row.get("draft_version")).longValue())throw DomainException.conflict("STALE_AMENDMENT");
    if(row.get("state").equals("ACCEPTED"))return summary(row);
    var c=source(request);pending(c);if(!row.get("state").equals("SENT")||!row.get("base_signature").equals(c.get("signature")))throw DomainException.conflict("AMENDMENT_SOURCE_CHANGED");
    var d=json.readValue((String)row.get("private_payload"),Draft.class);var projection=project(request,d,c);String encoded=json.writeValueAsString(projection);if(!json.readTree(encoded).equals(json.readTree((String)row.get("public_payload"))))throw DomainException.conflict("AMENDMENT_SOURCE_CHANGED");
    var q=quote(d.replacementQuoteId());var b=(Map<?,?>)c.get("booking");UUID offerId=UUID.randomUUID(),contractId=UUID.randomUUID();
    int revision=db.queryForObject("SELECT coalesce(max(revision),0)+1 FROM offer_revisions WHERE booking_id=?",Integer.class,booking);long publishedDraft=db.queryForObject("SELECT coalesce(max(draft_version),0)+1 FROM offer_revisions WHERE booking_id=?",Long.class,booking);
    var lines=d.services().stream().map(l->new OfferService.PublicLine(l.key(),l.name(),l.quantity(),l.nights(),l.unitPrice(),l.unit(),OfferService.amount(l),l.policy())).toList();int total=lines.stream().mapToInt(OfferService.PublicLine::total).sum();
    var offer=new OfferService.PublicOffer(offerId,booking,revision,publishedDraft,d.title(),(String)q.get("currency"),((java.sql.Date)q.get("departure")).toLocalDate().toString(),json.readTree((String)q.get("payload")).path("input").path("pax").asInt(),d.itinerary(),d.returnRoute(),lines,total,d.message(),Instant.now().toString());
    db.update("INSERT INTO offer_revisions(id,booking_id,revision,draft_version,payload,sent_by,source_quote_id) VALUES(?,?,?,?,?,?,?)",offerId,booking,revision,publishedDraft,json.writeValueAsString(offer),"amendment:"+row.get("sent_by"),d.replacementQuoteId());
    int contractVersion=db.queryForObject("SELECT coalesce(max(version),0)+1 FROM contract_documents WHERE booking_id=?",Integer.class,booking);
    db.update("UPDATE contract_documents SET state='SUPERSEDED' WHERE booking_id=? AND state IN ('ACCEPTED','SENT','DRAFT')",booking);
    db.update("INSERT INTO contract_documents(id,booking_id,offer_id,version,filename,content,sha256,state,created_by,sent_at,accepted_by,accepted_at) VALUES(?,?,?,?,?,?,?,'ACCEPTED',?,now(),?,now())",contractId,booking,offerId,contractVersion,row.get("pdf_filename"),row.get("pdf_content"),row.get("pdf_sha256"),row.get("sent_by"),customer);
    long retained=((Number)projection.get("retainedMinor")).longValue(),refund=((Number)projection.get("refundMinor")).longValue(),applied=((Number)projection.get("appliedMinor")).longValue();
    db.update("INSERT INTO amendment_settlements(request_id,booking_id,amendment_id,retired_minor,retained_minor,refund_minor,payload) VALUES(?,?,?,?,?,?,?)",request,booking,input.amendmentId(),retained+refund,retained,refund,encoded);
    db.update("UPDATE bookings SET quote_id=?,currency=?,total=?,deposit=?,departure=?,title=?,quote_payload=?,service_revision=service_revision+1,updated_at=now() WHERE id=?",d.replacementQuoteId(),q.get("currency"),q.get("total"),q.get("deposit"),q.get("departure"),d.title(),q.get("payload"),booking);
    db.update("UPDATE staged_payment_requests SET status='CANCELLED' WHERE booking_id=? AND status='OPEN'",booking);
    long allocationVersion=db.queryForObject("SELECT coalesce(max(version),0)+1 FROM payment_allocations WHERE booking_id=?",Long.class,booking);int cancellationCount=db.queryForObject("SELECT count(*) FROM cancelled_services WHERE booking_id=?",Integer.class,booking);
    db.update("INSERT INTO payment_allocations(booking_id,contract_id,version,paid_minor,payload,cancellation_count,updated_by) VALUES(?,?,?,?,?,?,?) ON CONFLICT(booking_id) DO UPDATE SET contract_id=excluded.contract_id,version=excluded.version,paid_minor=excluded.paid_minor,payload=excluded.payload,cancellation_count=excluded.cancellation_count,updated_by=excluded.updated_by,updated_at=now()",booking,contractId,allocationVersion,applied,json.writeValueAsString(d.allocations()),cancellationCount,customer.toString());
    db.update("UPDATE revised_payment_plans SET state='SUPERSEDED' WHERE booking_id=? AND state<>'SUPERSEDED'",booking);
    long planVersion=db.queryForObject("SELECT coalesce(max(version),0)+1 FROM revised_payment_plans WHERE booking_id=?",Long.class,booking);
    var plan=Map.of("contractId",contractId,"services",lines,"currency",q.get("currency"),"totalMinor",total*100L,"paidMinor",applied,"remainingMinor",total*100L-applied,"allocationVersion",allocationVersion,"cancellationCount",cancellationCount);
    db.update("INSERT INTO revised_payment_plans(id,booking_id,contract_id,version,payload,cancellation_count,state,created_by,accepted_at,accepted_by) VALUES(?,?,?,?,?,?,'ACCEPTED',?,now(),?)",UUID.randomUUID(),booking,contractId,planVersion,json.writeValueAsString(plan),cancellationCount,row.get("sent_by"),customer);
    db.update("UPDATE amendment_versions SET state='ACCEPTED',accepted_by=?,accepted_at=now(),applied_contract_id=? WHERE id=?",customer,contractId,input.amendmentId());
    db.update("UPDATE booking_requests SET status='ACCEPTED',response=?,resolved_at=now() WHERE id=? AND status='PENDING'",d.message(),request);
    audit(booking,customer.toString(),"AMENDMENT_ACCEPTED");notify(booking,"AMENDMENT_ACCEPTED");return summary(version(input.amendmentId()));
  }
  @Transactional public Object decline(UUID booking,UUID customer,Decline d){
    owned(booking,customer);var candidate=version(d.amendmentId());if(!booking.equals(candidate.get("booking_id")))throw DomainException.missing();UUID request=(UUID)candidate.get("request_id");lock(request);var row=version(d.amendmentId());
    if(((Number)row.get("draft_version")).longValue()!=d.version())throw DomainException.conflict("STALE_AMENDMENT");if(row.get("state").equals("DECLINED"))return summary(row);
    if(!row.get("state").equals("SENT")||!request(request).get("status").equals("PENDING"))throw DomainException.conflict("AMENDMENT_NOT_PENDING");
    db.update("UPDATE amendment_versions SET state='DECLINED' WHERE id=?",d.amendmentId());db.update("UPDATE booking_requests SET status='DECLINED',response=?,resolved_at=now() WHERE id=?",d.message(),request);audit(booking,customer.toString(),"AMENDMENT_DECLINED");notify(booking,"AMENDMENT_DECLINED");return summary(version(d.amendmentId()));
  }
  private void audit(UUID booking,String actor,String action){db.update("INSERT INTO booking_audit(id,booking_id,actor,action,note) VALUES(?,?,?,?,?)",UUID.randomUUID(),booking,actor,action,"");}
  private void notify(UUID booking,String event){for(String channel:List.of("EMAIL","PUSH"))db.update("INSERT INTO notification_outbox(id,customer_id,booking_id,channel,event,body,status) SELECT ?,customer_id,id,?,?,'Review the update in your account.',? FROM bookings WHERE id=?",UUID.randomUUID(),channel,event,channel.equals("EMAIL")&&email?"QUEUED":"WAITING_CONFIGURATION",booking);}
}
