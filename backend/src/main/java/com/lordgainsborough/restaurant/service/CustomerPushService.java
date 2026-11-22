package com.lordgainsborough.restaurant.service;
import com.lordgainsborough.restaurant.security.PasswordResetQueuePayloadEncryptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
@Service
public class CustomerPushService {
 private final JdbcTemplate jdbc;private final TransactionTemplate transactions;private final PasswordResetQueuePayloadEncryptor encryption;
 private final boolean enabled;private final boolean verified;private final String accessToken;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
 public CustomerPushService(DataSource source,PlatformTransactionManager manager,PasswordResetQueuePayloadEncryptor encryption,
 @Value("${app.notifications.enabled:false}") boolean enabled,@Value("${app.notifications.delivery-verified:false}") boolean verified,@Value("${app.notifications.expo-access-token:}") String accessToken) {
 this.jdbc=new JdbcTemplate(source);this.transactions=new TransactionTemplate(manager);this.encryption=encryption;this.enabled=enabled;this.verified=verified;this.accessToken=accessToken;
 }
 public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
 public void register(String userId,String session,String deviceId,String token,java.time.Instant expiresAt){
 if(!enabled||!verified||accessToken.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Order notifications are not enabled");
 if(deviceId==null||!deviceId.matches("[A-Za-z0-9_-]{1,64}")||token==null||!token.matches("(?:Expo|Exponent)PushToken\\[[A-Za-z0-9_-]{1,180}\\]"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid device registration");
 transactions.executeWithoutResult(tx->{
 jdbc.query("SELECT id FROM users WHERE id=? FOR UPDATE",(rs,n)->rs.getString(1),userId);
 var owner=jdbc.queryForList("SELECT user_id FROM customer_push_devices WHERE device_id=? OR token_hash=?",String.class,deviceId,hash(token));
 if(owner.stream().anyMatch(id->!id.equals(userId)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Device registration unavailable");
 jdbc.update("DELETE FROM customer_push_devices WHERE token_hash=? AND device_id<>? AND user_id=?",hash(token),deviceId,userId);
 jdbc.update("INSERT INTO customer_push_devices(device_id,user_id,session_hash,session_expires_at,token_hash,encrypted_token) VALUES (?,?,?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET session_hash=excluded.session_hash,session_expires_at=excluded.session_expires_at,token_hash=excluded.token_hash,encrypted_token=excluded.encrypted_token,opted_in=TRUE,updated_at=CURRENT_TIMESTAMP WHERE customer_push_devices.user_id=excluded.user_id",deviceId,userId,hash(session),java.sql.Timestamp.from(expiresAt),hash(token),encryption.encrypt(token,"push:"+deviceId));
 });
 }
 public void remove(String userId,String deviceId){jdbc.update("DELETE FROM customer_push_devices WHERE device_id=? AND user_id=?",deviceId,userId);}
 record Job(long id,String deviceId,String token,String orderId,String status,int attempt){}
 @Scheduled(initialDelay=15000,fixedDelay=15000)
 public void deliver(){
 jdbc.update("DELETE FROM customer_push_devices WHERE session_expires_at<=CURRENT_TIMESTAMP");
 if(!enabled||!verified||accessToken.isBlank())return;
 jdbc.update("DELETE FROM order_push_outbox WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '1 day'");
 var jobs=jdbc.query("""
 UPDATE order_push_outbox j SET state='PROCESSING',attempts=attempts+1,lease_until=CURRENT_TIMESTAMP+INTERVAL '60 seconds'
 WHERE j.id IN (SELECT id FROM order_push_outbox WHERE attempts<5 AND ((state='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP) OR (state='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP)) ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 10)
 RETURNING id,device_id,order_id,status,attempts
 """,(rs,n)->new Job(rs.getLong("id"),rs.getString("device_id"),null,rs.getString("order_id"),rs.getString("status"),rs.getInt("attempts")));
 checkReceipts();
 for(var job:jobs){
 // Lock the user/device through delivery so opt-out, logout and deletion cannot race the ownership check.
 transactions.executeWithoutResult(tx->{
 var devices=jdbc.queryForList("""
 SELECT d.encrypted_token FROM customer_push_devices d JOIN users u ON u.id=d.user_id JOIN customer_orders o ON o.id=?
 WHERE d.device_id=? AND d.opted_in AND d.session_expires_at>CURRENT_TIMESTAMP AND lower(u.email)=lower(o.customer_email)
 AND NOT EXISTS(SELECT 1 FROM revoked_account_sessions r WHERE r.token_hash=d.session_hash AND r.expires_at>CURRENT_TIMESTAMP)
 FOR UPDATE OF d
 """,String.class,job.orderId(),job.deviceId());
 if(devices.isEmpty()){finish(job,"SKIPPED");return;}
 try{
 String token=encryption.decrypt(devices.getFirst(),"push:"+job.deviceId());
 String body=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("to",token,"title","Order update","body","Open Lord Gainsborough to check your pickup order.","channelId","orders","collapseId","order-event-"+job.id(),"tag","order-event-"+job.id(),"ttl",3600,"data",java.util.Map.of("orderId",job.orderId(),"eventId",Long.toString(job.id()))));
 var request=HttpRequest.newBuilder(URI.create("https://exp.host/--/api/v2/push/send")).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").header("Authorization","Bearer "+accessToken).POST(HttpRequest.BodyPublishers.ofString(body)).build();
 var response=http.send(request,HttpResponse.BodyHandlers.ofString());
 var parsed=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body()).path("data");
 if(parsed.isArray())parsed=parsed.path(0);
 if(response.statusCode()==200&&"ok".equals(parsed.path("status").asText())&&!parsed.path("id").asText().isBlank())jdbc.update("UPDATE order_push_outbox SET state='SUBMITTED',provider_ticket=?,lease_until=NULL,next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '60 seconds' WHERE id=? AND attempts=?",parsed.path("id").asText(),job.id(),job.attempt());
 else if("DeviceNotRegistered".equals(parsed.path("details").path("error").asText())){jdbc.update("DELETE FROM customer_push_devices WHERE device_id=?",job.deviceId());}
 else finish(job,job.attempt()>=5?"FAILED":"PENDING");
 }catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();finish(job,job.attempt()>=5?"FAILED":"PENDING");}
 });
 }
 }
 private void checkReceipts(){
 var rows=jdbc.queryForList("SELECT id,device_id,provider_ticket,receipt_checks FROM order_push_outbox WHERE state='SUBMITTED' AND next_attempt_at<=CURRENT_TIMESTAMP LIMIT 20");
 for(var row:rows){long id=((Number)row.get("id")).longValue();String ticket=(String)row.get("provider_ticket");
 try{
 String body=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("ids",java.util.List.of(ticket)));
 var request=HttpRequest.newBuilder(URI.create("https://exp.host/--/api/v2/push/getReceipts")).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").header("Authorization","Bearer "+accessToken).POST(HttpRequest.BodyPublishers.ofString(body)).build();
 var response=http.send(request,HttpResponse.BodyHandlers.ofString());var result=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body()).path("data").path(ticket);
 if(response.statusCode()==200&&"ok".equals(result.path("status").asText())){jdbc.update("UPDATE order_push_outbox SET state='SENT' WHERE id=? AND state='SUBMITTED'",id);continue;}
 if("DeviceNotRegistered".equals(result.path("details").path("error").asText())){jdbc.update("DELETE FROM customer_push_devices WHERE device_id=?",row.get("device_id"));continue;}
 if("error".equals(result.path("status").asText())){jdbc.update("UPDATE order_push_outbox SET state='FAILED' WHERE id=? AND state='SUBMITTED'",id);continue;}
 }catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();}
 jdbc.update("UPDATE order_push_outbox SET receipt_checks=receipt_checks+1,state=CASE WHEN receipt_checks>=29 THEN 'FAILED' ELSE 'SUBMITTED' END,next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '60 seconds' WHERE id=? AND state='SUBMITTED'",id);
 }
 }
 private void finish(Job job,String state){jdbc.update("UPDATE order_push_outbox SET state=?,lease_until=NULL,next_attempt_at=CURRENT_TIMESTAMP+(? * INTERVAL '15 seconds') WHERE id=? AND state='PROCESSING' AND attempts=?",state,(int)Math.min(16,Math.pow(2,job.attempt()-1)),job.id(),job.attempt());}
}
