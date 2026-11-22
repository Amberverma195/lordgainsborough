package com.lordgainsborough.restaurant.controller;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
public class ReadinessController {
 private final ApplicationAvailability availability;
 private final JdbcTemplate jdbc;
 public ReadinessController(ApplicationAvailability availability, javax.sql.DataSource source) {this.availability=availability;this.jdbc=new JdbcTemplate(source);}
 @GetMapping("/api/health/readiness")
 public ResponseEntity<Map<String,String>> readiness() {
  if(availability.getReadinessState()!=ReadinessState.ACCEPTING_TRAFFIC)return unavailable();
  try {if(!Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1",Integer.class)))return unavailable();}
  catch(RuntimeException unavailable){return unavailable();}
  return ResponseEntity.ok(Map.of("status","UP"));
 }
 private ResponseEntity<Map<String,String>> unavailable(){return ResponseEntity.status(503).body(Map.of("status","OUT_OF_SERVICE"));}
}
