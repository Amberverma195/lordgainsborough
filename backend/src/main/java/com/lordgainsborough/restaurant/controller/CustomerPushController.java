package com.lordgainsborough.restaurant.controller;
import com.lordgainsborough.restaurant.service.*;
import com.lordgainsborough.restaurant.security.JwtUtil;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@RestController
@RequestMapping("/api/auth/push-devices")
public class CustomerPushController {
 private final UserAuthStore users;private final JwtUtil jwt;private final CustomerPushService pushes;
 public CustomerPushController(UserAuthStore users,JwtUtil jwt,CustomerPushService pushes){this.users=users;this.jwt=jwt;this.pushes=pushes;}
 record Registration(String deviceId,String pushToken){}
 private com.lordgainsborough.restaurant.model.User owner(String header){if(header==null||!header.startsWith("Bearer "))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);return users.authenticate(header.substring(7),jwt).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED));}
 @PostMapping public ResponseEntity<Void> register(@RequestHeader(value="Authorization",required=false)String header,@RequestBody Registration request){var user=owner(header);pushes.register(user.getId(),header.substring(7),request.deviceId(),request.pushToken(),jwt.getSessionExpiresAt(header.substring(7)));return ResponseEntity.noContent().build();}
 @DeleteMapping("/{deviceId}") public ResponseEntity<Void> remove(@RequestHeader(value="Authorization",required=false)String header,@PathVariable String deviceId){var user=owner(header);pushes.remove(user.getId(),deviceId);return ResponseEntity.noContent().build();}
}
