package com.lordgainsborough.restaurant.controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import com.lordgainsborough.restaurant.service.UserAuthStore;
import com.lordgainsborough.restaurant.security.*;
import java.util.Map;
@RestController
@RequestMapping("/api/reservation-policy")
public class ReservationPolicyController {
 private final JdbcTemplate jdbc;private final UserAuthStore users;private final JwtUtil jwt;
 public ReservationPolicyController(javax.sql.DataSource source,UserAuthStore users,JwtUtil jwt){jdbc=new JdbcTemplate(source);this.users=users;this.jwt=jwt;}
 @GetMapping public Map<String,Object> read(){return jdbc.queryForObject("SELECT last_seating_minutes,advance_notice_minutes,management_approved FROM reservation_seating_policy WHERE id='default'",(rs,n)->Map.of("lastSeatingMinutes",rs.getInt(1),"advanceNoticeMinutes",rs.getInt(2),"managementApproved",rs.getBoolean(3),"timezone","America/Toronto"));}
 public record Policy(int lastSeatingMinutes,int advanceNoticeMinutes,boolean managementApproved){}
 @PutMapping public Map<String,Object> update(@RequestHeader(value="Authorization",required=false)String header,@RequestBody Policy policy){
 if(header==null||!header.startsWith("Bearer ")||users.authenticate(header.substring(7),jwt).filter(user->RoleAccess.isAdmin(user.getRole())).isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN);
 if(policy.lastSeatingMinutes()<1||policy.lastSeatingMinutes()>480||policy.advanceNoticeMinutes()<1||policy.advanceNoticeMinutes()>10080)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid seating policy");
 jdbc.update("UPDATE reservation_seating_policy SET last_seating_minutes=?,advance_notice_minutes=?,management_approved=? WHERE id='default'",policy.lastSeatingMinutes(),policy.advanceNoticeMinutes(),policy.managementApproved());return read();
 }
}
