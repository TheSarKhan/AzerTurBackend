package az.azertur.operations;

import az.azertur.shared.DomainException;
import java.security.Principal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/operations")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="operationsAuth")
public class RequestWorkspaceController {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final String bootstrap;
  public RequestWorkspaceController(JdbcTemplate db,ObjectMapper json,@org.springframework.beans.factory.annotation.Value("${app.security.operations-user}") String bootstrap){this.db=db;this.json=json;this.bootstrap=bootstrap;}

  @GetMapping("/request-workspace")
  public Object workspace(Principal actor) {
    var rows=db.queryForList("""
      SELECT b.id,b.status,b.departure,b.title,b.created_at,b.assigned_to,c.name,c.email,c.locale,
        b.quote_payload,d.payload AS draft_payload,
        (SELECT count(*) FROM offer_revisions o WHERE o.booking_id=b.id AND o.source_quote_id=b.quote_id) AS sent,
        (SELECT kind FROM booking_requests r WHERE r.booking_id=b.id AND r.status='PENDING' LIMIT 1) AS pending_kind,
        (SELECT c.state FROM contract_documents c JOIN offer_revisions o ON o.id=c.offer_id WHERE c.booking_id=b.id AND o.source_quote_id=b.quote_id ORDER BY version DESC LIMIT 1) AS contract_state
      FROM bookings b JOIN customers c ON c.id=b.customer_id
      LEFT JOIN offer_drafts d ON d.booking_id=b.id ORDER BY b.created_at DESC,b.id
      """);
    for(var row:rows){
      row.put("departure",((java.sql.Date)row.get("departure")).toLocalDate().toString());
      var q=json.readTree((String)row.remove("quote_payload"));
      row.put("travelers",q.path("input").path("pax").asInt());
      row.put("route",q.path("input").path("cities"));
      row.put("services",q.path("lines"));
      Object draft=row.remove("draft_payload");
      row.put("hasDraft",draft!=null);
      String stage=String.valueOf(row.get("status"));
      if(!Set.of("CANCELLED","REJECTED").contains(stage)){
        if("CANCEL".equals(row.get("pending_kind")))stage="CANCELLATION_REVIEW";
        else if(row.get("pending_kind")!=null)stage="CHANGE_REVIEW";
        else if(!stage.equals("CONFIRMED") && "ACCEPTED".equals(row.get("contract_state")))stage="CONTRACT_ACCEPTED";
        else if(!stage.equals("CONFIRMED") && ((Number)row.get("sent")).intValue()>0)stage="OFFER_SENT";
        else if(!stage.equals("CONFIRMED") && draft!=null)stage="IN_PREPARATION";
        else if(stage.equals("PENDING_REVIEW"))stage="NEEDS_REVIEW";
      }
      row.put("stage",stage);
    }
    return Map.of("actor",actor.getName(),"admin",admin(actor),"staff",db.queryForList("SELECT username,name FROM staff_accounts WHERE enabled=true UNION SELECT ?,? ORDER BY name",bootstrap,bootstrap),"rows",rows,"activity",db.queryForList("""
      SELECT a.id,a.booking_id,coalesce(c.name,a.actor) AS actor,a.action,a.created_at FROM booking_audit a
      LEFT JOIN customers c ON c.email=a.actor OR c.id::text=a.actor
      ORDER BY a.created_at DESC,a.id LIMIT 12
      """));
  }
  private boolean admin(Principal p){return p instanceof org.springframework.security.core.Authentication a && a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ROLE_ADMIN"));}
  public record Assignment(boolean assign,String expectedOwner,String assignee){}
  @PostMapping("/bookings/{id}/assignment")
  @Transactional
  public void assign(@PathVariable UUID id,@RequestBody Assignment body,Principal actor){
    var row=db.queryForList("SELECT assigned_to FROM bookings WHERE id=? FOR UPDATE",id);
    if(row.isEmpty())throw DomainException.missing();
    String owner=(String)row.getFirst().get("assigned_to");
    if(!Objects.equals(owner,body.expectedOwner()))throw DomainException.conflict("ASSIGNMENT_CHANGED");
    if(owner!=null&&!owner.equals(actor.getName())&&!admin(actor))throw DomainException.conflict("ASSIGNED_TO_ANOTHER_EMPLOYEE");
    String next=body.assign()?(body.assignee()==null?actor.getName():body.assignee()):null;
    if(next!=null&&!next.equals(actor.getName())&&!admin(actor))throw DomainException.conflict("ASSIGNMENT_NOT_ALLOWED");
    if(next!=null&&!next.equals(bootstrap)&&db.queryForObject("SELECT count(*) FROM staff_accounts WHERE username=? AND enabled=true",Integer.class,next)==0)throw DomainException.bad("STAFF_NOT_ACTIVE");
    if(Objects.equals(owner,next))return;
    db.update("UPDATE bookings SET assigned_to=?,updated_at=now() WHERE id=?",next,id);
    db.update("INSERT INTO booking_audit(id,booking_id,actor,action,note) VALUES(?,?,?,?,?)",UUID.randomUUID(),id,actor.getName(),body.assign()?"ASSIGNED":"UNASSIGNED",next==null?"":next);
  }
}
