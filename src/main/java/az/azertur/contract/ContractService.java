package az.azertur.contract;

import az.azertur.shared.DomainException;
import java.security.MessageDigest;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ContractService {
  private final JdbcTemplate db;
  private final boolean email;
  public ContractService(JdbcTemplate db, @Value("${app.notifications.email-enabled:false}") boolean email) { this.db = db; this.email = email; }

  private Map<String,Object> booking(UUID id) {
    return db.queryForList("SELECT * FROM bookings WHERE id=? FOR UPDATE", id).stream().findFirst().orElseThrow(DomainException::missing);
  }
  private UUID latestOffer(UUID id, Object quote) {
    return db.queryForList("SELECT id FROM offer_revisions WHERE booking_id=? AND source_quote_id=? ORDER BY revision DESC LIMIT 1", id, quote)
        .stream().findFirst().map(r -> (UUID)r.get("id")).orElseThrow(() -> DomainException.conflict("OFFER_REQUIRED"));
  }
  private void active(Map<String,Object> booking) {
    if (!booking.get("status").equals("PENDING_REVIEW") || ((Number)booking.get("paid")).intValue()!=0)
      throw DomainException.conflict("CONTRACT_NOT_EDITABLE");
    if (db.queryForObject("SELECT count(*) FROM booking_requests WHERE booking_id=? AND status='PENDING'", Integer.class, booking.get("id")) > 0)
      throw DomainException.conflict("REQUEST_PENDING");
  }
  private Map<String,Object> document(UUID booking, UUID id) {
    return db.queryForList("SELECT * FROM contract_documents WHERE booking_id=? AND id=?", booking, id).stream().findFirst().orElseThrow(DomainException::missing);
  }
  private Map<String,Object> summary(Map<String,Object> d) {
    var out = new LinkedHashMap<String,Object>();
    for (String key : List.of("id","booking_id","offer_id","version","filename","sha256","state","created_at","sent_at","accepted_at")) out.put(key, d.get(key));
    out.put("offer_revision", db.queryForObject("SELECT revision FROM offer_revisions WHERE id=?", Integer.class, d.get("offer_id")));
    out.put("current_offer", db.queryForObject("SELECT EXISTS(SELECT 1 FROM offer_revisions o JOIN bookings b ON b.id=o.booking_id WHERE o.id=? AND o.source_quote_id=b.quote_id AND o.revision=(SELECT max(revision) FROM offer_revisions WHERE booking_id=b.id AND source_quote_id=b.quote_id))", Boolean.class, d.get("offer_id")));
    return out;
  }
  public Object operator(UUID booking) {
    if (db.queryForObject("SELECT count(*) FROM bookings WHERE id=?", Integer.class, booking)==0) throw DomainException.missing();
    return db.queryForList("SELECT * FROM contract_documents WHERE booking_id=? ORDER BY version DESC LIMIT 1", booking).stream().findFirst().map(this::summary).orElse(null);
  }
  @Transactional
  public Object upload(UUID id, MultipartFile file, String actor) throws Exception {
    var b = booking(id); active(b); var offer=latestOffer(id,b.get("quote_id"));
    if (file.isEmpty() || file.getSize()>5_000_000) throw DomainException.bad("INVALID_PDF_SIZE");
    byte[] bytes=file.getBytes();
    if (bytes.length<8 || !new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
      throw DomainException.bad("INVALID_PDF");
    try (var pdf = Loader.loadPDF(bytes)) {
      if (pdf.isEncrypted() || pdf.getNumberOfPages() < 1 || pdf.getNumberOfPages() > 100) throw DomainException.bad("INVALID_PDF");
    } catch (java.io.IOException e) {
      throw DomainException.bad("INVALID_PDF");
    }
    String name=Optional.ofNullable(file.getOriginalFilename()).orElse("contract.pdf").replaceAll("[^a-zA-Z0-9._-]", "_");
    if (name.length()>160) name=name.substring(name.length()-160);
    String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    int version=db.queryForObject("SELECT coalesce(max(version),0)+1 FROM contract_documents WHERE booking_id=?",Integer.class,id);
    db.update("UPDATE contract_documents SET state='SUPERSEDED' WHERE booking_id=? AND state='DRAFT'",id);
    UUID document=UUID.randomUUID();
    db.update("INSERT INTO contract_documents(id,booking_id,offer_id,version,filename,content,sha256,state,created_by) VALUES(?,?,?,?,?,?,?,'DRAFT',?)",document,id,offer,version,name,bytes,hash,actor);
    audit(id,actor,"CONTRACT_DRAFT_SAVED",document.toString());
    return summary(document(id,document));
  }
  private void audit(UUID id,String actor,String action,String note) {
    db.update("INSERT INTO booking_audit(id,booking_id,actor,action,note) VALUES(?,?,?,?,?)",UUID.randomUUID(),id,actor,action,note);
  }
  @Transactional
  public Object send(UUID id, UUID doc, String actor) {
    var b=booking(id); var d=document(id,doc);
    if (d.get("state").equals("SENT") && d.get("offer_id").equals(latestOffer(id,b.get("quote_id")))) return summary(d);
    active(b);
    if (!d.get("state").equals("DRAFT") || !d.get("offer_id").equals(latestOffer(id,b.get("quote_id"))))
      throw DomainException.conflict("STALE_CONTRACT");
    db.update("UPDATE contract_documents SET state='SUPERSEDED' WHERE booking_id=? AND state IN ('SENT','ACCEPTED')",id);
    db.update("UPDATE contract_documents SET state='SENT',sent_at=now() WHERE id=?",doc);
    audit(id,actor,"CONTRACT_SENT",doc.toString());
    db.update("INSERT INTO notification_outbox(id,customer_id,booking_id,channel,event,body,status) VALUES(?,?,?,'EMAIL','CONTRACT_SENT',?,?)",
        UUID.randomUUID(),b.get("customer_id"),id,"A contract is ready to review in your account.",email ? "QUEUED" : "WAITING_CONFIGURATION");
    return summary(document(id,doc));
  }
  public Object customer(UUID id, UUID customer) {
    var list=db.queryForList("SELECT d.* FROM contract_documents d JOIN bookings b ON b.id=d.booking_id JOIN offer_revisions o ON o.id=d.offer_id WHERE b.id=? AND b.customer_id=? AND (b.status='PENDING_REVIEW' OR d.state='ACCEPTED') AND o.source_quote_id=b.quote_id AND o.revision=(SELECT max(revision) FROM offer_revisions WHERE booking_id=b.id AND source_quote_id=b.quote_id) AND d.state IN ('SENT','ACCEPTED') ORDER BY d.version DESC LIMIT 1",id,customer);
    return list.stream().findFirst().map(this::summary).orElse(null);
  }
  public byte[] operatorPdf(UUID id, UUID doc) { return (byte[])document(id,doc).get("content"); }
  public byte[] customerPdf(UUID id,UUID doc,UUID customer) {
    Object item=customer(id,customer);
    if (!(item instanceof Map<?,?> m) || !doc.equals(m.get("id"))) throw DomainException.missing();
    return operatorPdf(id,doc);
  }
  @Transactional
  public Object accept(UUID id,UUID doc,UUID customer,String hash) {
    var b=booking(id);
    if (!customer.equals(b.get("customer_id"))) throw DomainException.missing();
    active(b);
    var d=document(id,doc);
    if (!doc.equals(((Map<?,?>)Optional.ofNullable(customer(id,customer)).orElseThrow(DomainException::missing)).get("id")) || !Objects.equals(hash,d.get("sha256")))
      throw DomainException.conflict("STALE_CONTRACT");
    if (d.get("state").equals("ACCEPTED")) return summary(d);
    if (!d.get("state").equals("SENT")) throw DomainException.conflict("STALE_CONTRACT");
    db.update("UPDATE contract_documents SET state='ACCEPTED',accepted_by=?,accepted_at=now() WHERE id=?",customer,doc);
    audit(id,customer.toString(),"CONTRACT_ACCEPTED",doc+" "+hash);
    return summary(document(id,doc));
  }
}
