package az.azertur.partner;
import az.azertur.shared.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
@RestController
public class PartnerController {
 private final JdbcTemplate db;public PartnerController(JdbcTemplate db){this.db=db;}
 public record Partner(UUID id,@NotBlank @Size(max=160) String name,@Pattern(regexp="HOTEL|TRANSPORT|GUIDE|ACTIVITY|OTHER") @NotNull String kind,@NotNull @Email @Size(max=254) String email,@NotNull @Size(max=60) String phone,@NotNull @Size(max=4000) String notes,boolean active,@Min(0) long version){}
 public record Service(UUID id,@NotNull UUID partnerId,@NotBlank @Size(max=160) String name,@NotNull @Pattern(regexp="hotel|transport|guide|transfer|activity") String serviceKey,@NotBlank @Size(max=160) String location,@Pattern(regexp="EUR|USD|AZN") @NotNull String currency,@Min(0) @Max(100000000000L) long priceMinor,@NotNull LocalDate availableFrom,@NotNull LocalDate availableTo,@Min(0) int capacity,@NotBlank @Size(max=4000) String policy,boolean active,@Min(0) long version){}
 @GetMapping("/api/v1/operations/partners") Object list(){return Map.of("partners",db.queryForList("SELECT id,name,kind,email,phone,notes,active,version,(sync_key_hash IS NOT NULL) AS sync_enabled FROM partners ORDER BY name"),"services",dates(db.queryForList("SELECT s.*,p.name AS partner_name,p.active AS partner_active FROM partner_services s JOIN partners p ON p.id=s.partner_id ORDER BY p.name,s.name")));}
 @PostMapping("/api/v1/operations/partners") @Transactional Object save(@Valid @RequestBody Partner p,Principal actor){
  UUID id=p.id()==null?UUID.randomUUID():p.id();
  db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",id.toString());
  var old=db.queryForList("SELECT version FROM partners WHERE id=? FOR UPDATE",id);
  long version=old.isEmpty()?0:((Number)old.getFirst().get("version")).longValue();
  if(version!=p.version())throw DomainException.conflict("STALE_PARTNER");
  db.update("INSERT INTO partners(id,name,kind,email,phone,notes,active,version) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,kind=excluded.kind,email=excluded.email,phone=excluded.phone,notes=excluded.notes,active=excluded.active,version=excluded.version",id,p.name(),p.kind(),p.email(),p.phone(),p.notes(),p.active(),version+1);audit(actor.getName(),"PARTNER_UPDATED",id);return list();
 }
 @PostMapping("/api/v1/operations/partner-services") @Transactional Object saveService(@Valid @RequestBody Service s,Principal actor){upsert(s,actor.getName(),false);return list();}
 private void upsert(Service s,String actor,boolean sync){
  if(s.availableTo().isBefore(s.availableFrom()))throw DomainException.bad("INVALID_DATE_RANGE");
  var partner=db.queryForList("SELECT active FROM partners WHERE id=? FOR UPDATE",s.partnerId());
  if(partner.isEmpty())throw DomainException.missing();
  if(sync&&!Boolean.TRUE.equals(partner.getFirst().get("active")))throw DomainException.conflict("PARTNER_INACTIVE");
  UUID id=s.id()==null?UUID.randomUUID():s.id();var old=db.queryForList("SELECT * FROM partner_services WHERE id=? FOR UPDATE",id);
  long version=old.isEmpty()?0:((Number)old.getFirst().get("version")).longValue();
  if(version!=s.version()||!old.isEmpty()&&!s.partnerId().equals(old.getFirst().get("partner_id")))throw DomainException.conflict("STALE_SERVICE");
  db.update("INSERT INTO partner_services(id,partner_id,name,service_key,location,currency,price_minor,available_from,available_to,capacity,policy,active,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,service_key=excluded.service_key,location=excluded.location,currency=excluded.currency,price_minor=excluded.price_minor,available_from=excluded.available_from,available_to=excluded.available_to,capacity=excluded.capacity,policy=excluded.policy,active=excluded.active,version=excluded.version,updated_at=now()",id,s.partnerId(),s.name(),s.serviceKey(),s.location(),s.currency(),s.priceMinor(),s.availableFrom(),s.availableTo(),s.capacity(),s.policy(),s.active(),version+1);audit(actor,"PARTNER_SERVICE_UPDATED",id);
 }
 private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 public record KeyVersion(@Min(1) long version){}
 @PostMapping("/api/v1/operations/partners/{id}/sync-key") @Transactional Object rotate(@PathVariable UUID id,@Valid @RequestBody KeyVersion body,Principal actor){
  byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String key=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  if(db.update("UPDATE partners SET sync_key_hash=?,version=version+1 WHERE id=? AND version=?",hash(key),id,body.version())!=1)throw DomainException.conflict("STALE_PARTNER");audit(actor.getName(),"PARTNER_KEY_ROTATED",id);return Map.of("key",key);
 }
 @PostMapping("/api/v1/partner-sync/{id}") @Transactional Object sync(@PathVariable UUID id,@RequestHeader(value="X-Partner-Key",required=false) String key,@Valid @RequestBody Service s){
  var rows=db.queryForList("SELECT sync_key_hash FROM partners WHERE id=? AND active=true FOR UPDATE",id);
  if(key==null||key.length()>100||rows.isEmpty()||rows.getFirst().get("sync_key_hash")==null||!MessageDigest.isEqual(hash(key).getBytes(),((String)rows.getFirst().get("sync_key_hash")).getBytes()))throw new DomainException(org.springframework.http.HttpStatus.UNAUTHORIZED,"INVALID_PARTNER_KEY");
  if(!id.equals(s.partnerId())||s.id()==null)throw DomainException.bad("INVALID_SERVICE");
  upsert(s,"partner:"+id,true);var row=db.queryForMap("SELECT id,version FROM partner_services WHERE id=? AND partner_id=?",s.id(),id);return row;
 }
 @GetMapping("/api/v1/partner-sync/{id}") Object inventory(@PathVariable UUID id,@RequestHeader(value="X-Partner-Key",required=false) String key){
  var rows=db.queryForList("SELECT sync_key_hash FROM partners WHERE id=? AND active=true",id);
  if(key==null||key.length()>100||rows.isEmpty()||rows.getFirst().get("sync_key_hash")==null||!MessageDigest.isEqual(hash(key).getBytes(),((String)rows.getFirst().get("sync_key_hash")).getBytes()))throw new DomainException(org.springframework.http.HttpStatus.UNAUTHORIZED,"INVALID_PARTNER_KEY");
  return dates(db.queryForList("SELECT id,partner_id,name,service_key,location,currency,price_minor,available_from,available_to,capacity,policy,active,version FROM partner_services WHERE partner_id=? ORDER BY name",id));
 }
 private List<Map<String,Object>> dates(List<Map<String,Object>> rows){for(var r:rows)for(String k:List.of("available_from","available_to"))if(r.get(k) instanceof java.sql.Date d)r.put(k,d.toLocalDate().toString());return rows;}
 private void audit(String actor,String action,UUID ref){db.update("INSERT INTO office_audit(id,actor,action,reference) VALUES(?,?,?,?)",UUID.randomUUID(),actor,action,ref.toString());}
}
