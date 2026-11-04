package az.azertur.document;

import az.azertur.shared.DomainException;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TravelDocumentService {
  private static final String METADATA="id,booking_id,document_group,service_revision,service_key,kind,version,title,filename,mime,sha256,starts_at,ends_at,timezone,instructions,state,created_at,published_at,previewed_by,octet_length(content) AS size";
  private final JdbcTemplate db;
  private final boolean email;
  public TravelDocumentService(JdbcTemplate db,@org.springframework.beans.factory.annotation.Value("${app.notifications.email-enabled:false}")boolean email){this.db=db;this.email=email;}
  public record Upload(String serviceKey,String kind,String title,OffsetDateTime startsAt,OffsetDateTime endsAt,String timezone,String instructions,UUID replaces){}
  private Map<String,Object> booking(UUID id, UUID owner, boolean lock){
    return db.queryForList("SELECT * FROM bookings WHERE id=?"+(owner==null?"":" AND customer_id=?")+(lock?" FOR UPDATE":""),owner==null?new Object[]{id}:new Object[]{id,owner})
      .stream().findFirst().orElseThrow(DomainException::missing);
  }
  private Map<String,Object> doc(UUID booking, UUID id, boolean customer){
    return db.queryForList("SELECT "+METADATA+" FROM travel_documents WHERE booking_id=? AND id=?"+(customer?" AND published_at IS NOT NULL":""),booking,id).stream().findFirst().orElseThrow(DomainException::missing);
  }
  private boolean cancelled(UUID booking,String key){
    return db.queryForObject("SELECT EXISTS(SELECT 1 FROM bookings WHERE id=? AND status IN ('CANCELLED','REJECTED')) OR EXISTS(SELECT 1 FROM cancelled_services s JOIN bookings b ON b.id=s.booking_id AND b.service_revision=s.service_revision WHERE b.id=? AND s.service_key=?)",Boolean.class,booking,booking,key);
  }
  private Instant instant(Object value){return value instanceof OffsetDateTime t?t.toInstant():((java.sql.Timestamp)value).toInstant();}
  private Map<String,Object> summary(Map<String,Object> row){
    var out=new LinkedHashMap<String,Object>();
    for(String key:List.of("id","booking_id","document_group","service_key","kind","version","title","filename","mime","sha256","starts_at","ends_at","timezone","instructions","state","published_at","created_at")) out.put(key,row.get(key));
    String state=(String)row.get("state");
    if(state.equals("PUBLISHED")) {
      if(!Objects.equals(row.get("service_revision"),db.queryForObject("SELECT service_revision FROM bookings WHERE id=?",Integer.class,row.get("booking_id"))))state="REQUIRES_REVIEW";
      else if(cancelled((UUID)row.get("booking_id"),(String)row.get("service_key"))) state="CANCELLED";
      else if(Instant.now().isAfter(instant(row.get("ends_at")))) state="EXPIRED";
      else if(Instant.now().isBefore(instant(row.get("starts_at")))) state="UPCOMING";
      else state="ACTIVE";
    }
    out.put("status",state);
    out.put("verifiedAt",Instant.now());
    out.put("size",row.get("size"));
    var replacement=db.queryForList("SELECT id FROM travel_documents WHERE booking_id=? AND document_group=? AND state='PUBLISHED' AND id<>? AND service_revision=(SELECT service_revision FROM bookings WHERE id=travel_documents.booking_id)",row.get("booking_id"),row.get("document_group"),row.get("id"));
    out.put("replacementId",replacement.isEmpty()?null:replacement.getFirst().get("id"));
    return out;
  }
  public Object list(UUID id,UUID owner){
    booking(id,owner,false);
    return db.queryForList("SELECT "+METADATA+" FROM travel_documents WHERE booking_id=?"+(owner==null?"":" AND published_at IS NOT NULL")+" ORDER BY starts_at,version DESC",id).stream().map(this::summary).toList();
  }
  public Object get(UUID id,UUID document,UUID owner){booking(id,owner,false);return summary(doc(id,document,owner!=null));}
  public Map<String,Object> file(UUID id,UUID document,UUID owner){booking(id,owner,false);return db.queryForList("SELECT * FROM travel_documents WHERE booking_id=? AND id=?"+(owner!=null?" AND published_at IS NOT NULL":""),id,document).stream().findFirst().orElseThrow(DomainException::missing);}
  @Transactional
  public Object upload(UUID id,MultipartFile file,Upload in,String actor)throws Exception{
    var b=booking(id,null,true);
    if(Set.of("CANCELLED","REJECTED").contains(b.get("status")))throw DomainException.conflict("BOOKING_NOT_ACTIVE");
    if(!Set.of("flight","hotel","transport","guide","transfer","activity").contains(in.serviceKey()) || !Set.of("E_TICKET","BOARDING_PASS","HOTEL_VOUCHER","TRANSFER_VOUCHER","TOUR_VOUCHER","OTHER").contains(in.kind()))throw DomainException.bad("INVALID_DOCUMENT_TYPE");
    if(in.title()==null||in.title().isBlank()||in.title().length()>160||in.startsAt()==null||in.endsAt()==null||!in.endsAt().isAfter(in.startsAt())||in.instructions()==null||in.instructions().length()>2000)throw DomainException.bad("INVALID_DOCUMENT_DETAILS");
    try {ZoneId.of(in.timezone());}catch(Exception e){throw DomainException.bad("INVALID_TIMEZONE");}
    if(cancelled(id,in.serviceKey()))throw DomainException.conflict("SERVICE_CANCELLED");
    if(file.getSize()>10_000_000)throw DomainException.bad("DOCUMENT_SIZE_LIMIT");
    byte[] bytes=file.getBytes();String mime=OriginalFile.validate(bytes);
    String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    UUID group=UUID.randomUUID();
    if(in.replaces()!=null){var previous=doc(id,in.replaces(),false);if(!in.serviceKey().equals(previous.get("service_key"))||!in.kind().equals(previous.get("kind")))throw DomainException.conflict("DOCUMENT_GROUP_MISMATCH");group=(UUID)previous.get("document_group");}
    int version=db.queryForObject("SELECT coalesce(max(version),0)+1 FROM travel_documents WHERE booking_id=? AND document_group=?",Integer.class,id,group);
    String name=Optional.ofNullable(file.getOriginalFilename()).orElse("document").replaceAll("[^a-zA-Z0-9._-]","_");
    if(name.length()>150)name=name.substring(name.length()-150);
    String ext=mime.equals("application/pdf")?".pdf":mime.equals("image/png")?".png":".jpg";
    name=name.replaceAll("\\.[^.]*$","")+ext;
    UUID doc=UUID.randomUUID();
    db.update("INSERT INTO travel_documents(id,booking_id,document_group,service_revision,service_key,kind,version,title,filename,mime,content,sha256,starts_at,ends_at,timezone,instructions,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",doc,id,group,b.get("service_revision"),in.serviceKey(),in.kind(),version,in.title().trim(),name,mime,bytes,hash,in.startsAt(),in.endsAt(),in.timezone(),in.instructions(),actor);
    audit(id,actor,"TRAVEL_DOCUMENT_UPLOADED",doc);
    return summary(doc(id,doc,false));
  }
  @Transactional
  public Object preview(UUID id,UUID document,String hash,String actor){
    booking(id,null,true);var d=doc(id,document,false);
    if(!Objects.equals(hash,d.get("sha256"))||!d.get("state").equals("DRAFT"))throw DomainException.conflict("DOCUMENT_CHANGED");
    db.update("UPDATE travel_documents SET previewed_by=?,previewed_at=now() WHERE id=?",actor,document);
    audit(id,actor,"TRAVEL_DOCUMENT_PREVIEWED",document);return summary(doc(id,document,false));
  }
  @Transactional
  public Object publish(UUID id,UUID document,String hash,boolean customerChecked,String actor){
    var b=booking(id,null,true);var d=doc(id,document,false);
    if(!customerChecked||!Objects.equals(hash,d.get("sha256"))||!d.get("state").equals("DRAFT")||!actor.equals(d.get("previewed_by")))throw DomainException.conflict("DOCUMENT_PREVIEW_REQUIRED");
    if(!Objects.equals(d.get("service_revision"),b.get("service_revision")))throw DomainException.conflict("DOCUMENT_CHANGED");
    if(cancelled(id,(String)d.get("service_key")))throw DomainException.conflict("SERVICE_CANCELLED");
    int latest=db.queryForObject("SELECT max(version) FROM travel_documents WHERE booking_id=? AND document_group=?",Integer.class,id,d.get("document_group"));
    if(latest!=((Number)d.get("version")).intValue())throw DomainException.conflict("DOCUMENT_CHANGED");
    db.update("UPDATE travel_documents SET state='SUPERSEDED' WHERE booking_id=? AND document_group=? AND state='PUBLISHED'",id,d.get("document_group"));
    db.update("UPDATE travel_documents SET state='PUBLISHED',published_by=?,published_at=now() WHERE id=?",actor,document);
    audit(id,actor,"TRAVEL_DOCUMENT_PUBLISHED",document);
    for(String channel:List.of("EMAIL","PUSH"))db.update("INSERT INTO notification_outbox(id,customer_id,booking_id,channel,event,body,status) VALUES(?,?,?,?,'TRAVEL_DOCUMENT_PUBLISHED','A travel document is ready in your account.',?)",UUID.randomUUID(),b.get("customer_id"),id,channel,channel.equals("EMAIL")&&email?"QUEUED":"WAITING_CONFIGURATION");
    return summary(doc(id,document,false));
  }
  @Transactional
  public Object revoke(UUID id,UUID document,String actor){booking(id,null,true);doc(id,document,false);db.update("UPDATE travel_documents SET state='REVOKED',revoked_at=now() WHERE id=?",document);audit(id,actor,"TRAVEL_DOCUMENT_REVOKED",document);return summary(doc(id,document,false));}
  private void audit(UUID id,String actor,String action,UUID document){db.update("INSERT INTO booking_audit(id,booking_id,actor,action,note) VALUES(?,?,?,?,?)",UUID.randomUUID(),id,actor,action,document.toString());}
}
