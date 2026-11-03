package az.azertur.staff;
import az.azertur.shared.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/operations")
public class StaffController {
 private final JdbcTemplate db; private final String bootstrap;
 public StaffController(JdbcTemplate db,@Value("${app.security.operations-user}") String bootstrap){this.db=db;this.bootstrap=bootstrap;}
 public record Account(@NotBlank @Pattern(regexp="[a-zA-Z0-9._-]{3,100}") String username,@NotBlank @Size(max=100) String name,@NotNull @Pattern(regexp="ADMIN|OPERATOR|FINANCE") String role,@NotNull @Size(max=120) String password,boolean enabled,@Min(0) long version){}
 @GetMapping("/me") Object me(Authentication a){return Map.of("username",a.getName(),"roles",a.getAuthorities().stream().map(Object::toString).toList());}
 @GetMapping("/staff") Object list(){return Map.of("bootstrap",bootstrap,"accounts",db.queryForList("SELECT username,name,role,enabled,version,created_at FROM staff_accounts ORDER BY name"));}
 @PostMapping("/staff") @Transactional Object save(@Valid @RequestBody Account a,Principal user){
  db.queryForList("SELECT pg_advisory_xact_lock(38192271)");
  if(a.username().equals(bootstrap))throw DomainException.conflict("BOOTSTRAP_ACCOUNT_RESERVED");
  var old=db.queryForList("SELECT * FROM staff_accounts WHERE username=?",a.username());
  long version=old.isEmpty()?0:((Number)old.getFirst().get("version")).longValue();
  if(version!=a.version())throw DomainException.conflict("STALE_STAFF");
  if(a.username().equals(user.getName())&&(!a.enabled()||!a.role().equals("ADMIN")))throw DomainException.conflict("CANNOT_REMOVE_OWN_ADMIN");
  if(!a.password().isEmpty()&&a.password().length()<16 || old.isEmpty()&&a.password().length()<16)throw DomainException.bad("PASSWORD_TOO_SHORT");
  if(a.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw DomainException.bad("PASSWORD_TOO_LONG");
  String hash=a.password().isEmpty()?(String)old.getFirst().get("password_hash"):"{bcrypt}"+new BCryptPasswordEncoder().encode(a.password());
  db.update("INSERT INTO staff_accounts(username,name,password_hash,role,enabled,version) VALUES(?,?,?,?,?,?) ON CONFLICT(username) DO UPDATE SET name=excluded.name,password_hash=excluded.password_hash,role=excluded.role,enabled=excluded.enabled,version=excluded.version",a.username(),a.name(),hash,a.role(),a.enabled(),version+1);
  db.update("INSERT INTO office_audit(id,actor,action,reference) VALUES(?,?,?,?)",UUID.randomUUID(),user.getName(),"STAFF_UPDATED",a.username());return list();
 }
 @GetMapping("/staff/directory") Object directory(){return db.queryForList("SELECT username,name FROM staff_accounts WHERE enabled=true UNION SELECT ?,? ORDER BY name",bootstrap,bootstrap);}
 @GetMapping("/office-audit") Object audit(){return db.queryForList("SELECT actor,action,reference,created_at FROM office_audit ORDER BY created_at DESC LIMIT 200");}
}
