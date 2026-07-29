package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.dto.AdminCustomerSearchResult;
import com.lordgainsborough.restaurant.service.AdminCustomerSearchService;
import com.lordgainsborough.restaurant.service.KdsAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminCustomerController {

    private final AdminCustomerSearchService customerSearchService;
    private final KdsAccessService kdsAccessService;

    public AdminCustomerController(
            AdminCustomerSearchService customerSearchService,
            KdsAccessService kdsAccessService) {
        this.customerSearchService = customerSearchService;
        this.kdsAccessService = kdsAccessService;
    }

    @GetMapping("/customers/search")
    public ResponseEntity<?> searchCustomers(@RequestParam(value = "q", defaultValue = "") String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.length() < 2) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Enter at least 2 characters to search."));
        }
        List<AdminCustomerSearchResult> results = customerSearchService.search(trimmed);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/client-ip")
    public ResponseEntity<Map<String, String>> currentClientIp(HttpServletRequest request) {
        return ResponseEntity.ok(Map.of("ip", kdsAccessService.resolveClientIp(request)));
    }
}
