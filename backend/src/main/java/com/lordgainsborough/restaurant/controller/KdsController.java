package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/kds")
public class KdsController {
    private final KdsOrderStore orders;
    private final OrderRealtimeService realtime;
    private final Clock clock;
    @Autowired
    public KdsController(KdsOrderStore orders, OrderRealtimeService realtime, Clock clock) {
        this.orders = orders; this.realtime = realtime; this.clock = clock.withZone(ZoneId.systemDefault());
    }
    public KdsController(CustomerOrderRepository orders, OrderRealtimeService realtime,
            CustomerOrderPresentationService presentation, CustomerNoShowService noShows) {
        this(new MongoKdsOrderStore(orders,presentation,noShows),realtime,Clock.systemDefaultZone());
    }
    public KdsOrderStore getOrderStore() { return orders; }
    @GetMapping("/access")
    public ResponseEntity<Map<String,Object>> checkAccess() { return ResponseEntity.ok(Map.of("allowed",true)); }
    @GetMapping("/orders")
    public List<CustomerOrder> getOrders(@RequestParam(required=false) String date) {
        return orders.byPickupDate(date == null || date.isBlank() ? LocalDate.now(clock).toString() : date);
    }
    @GetMapping("/orders/all")
    public List<CustomerOrder> getAllOrders() { return orders.all(); }
    @PatchMapping("/orders/{id}/status")
    public ResponseEntity<CustomerOrder> updateStatus(@PathVariable String id, @RequestBody Map<String,Object> body) {
        String next = String.valueOf(body.getOrDefault("status","")).trim().toUpperCase(Locale.ROOT);
        if (!List.of("NEW","ACCEPTED","READY","PICKED_UP","NO_SHOW").contains(next)) return ResponseEntity.badRequest().build();
        return orders.status(id,next,current -> body.containsKey("averageReadyMinutes")
                ? readyMinutes(body.get("averageReadyMinutes"),current) : current,this::notifyUpdated)
                .map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
    @PatchMapping("/orders/{id}/ready-time")
    public ResponseEntity<CustomerOrder> updateReadyTime(@PathVariable String id, @RequestBody Map<String,Object> body) {
        return orders.readyTime(id,readyMinutes(body.get("averageReadyMinutes"),25),this::notifyUpdated)
                .map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
    @PostMapping("/orders/{id}/messages")
    public ResponseEntity<CustomerOrder.OrderMessage> addKdsMessage(@PathVariable String id, @RequestBody Map<String,String> body) {
        String text = body.get("text");
        if (text == null || text.isBlank()) return ResponseEntity.badRequest().build();
        var message = new CustomerOrder.OrderMessage(); message.setId(UUID.randomUUID().toString());
        message.setSenderRole("KDS"); message.setSenderName("Kitchen"); message.setText(text.trim());
        message.setCreatedAt(LocalDateTime.now(clock));
        return orders.message(id,message,saved -> { realtime.chatMessage(saved,message); notifyUpdated(saved); })
                .map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
    private void notifyUpdated(CustomerOrder saved) {
        if (orders instanceof PostgresKdsOrderStore) realtime.orderUpdatedWithCustomerCounts(saved);
        else realtime.orderUpdated(saved);
    }
    static int readyMinutes(Object value, int fallback) {
        try {
            BigDecimal number;
            if (value instanceof String text) number = new BigDecimal(new BigInteger(text));
            else if (value instanceof Number numeric) number = new BigDecimal(numeric.toString());
            else return OrderController.normalizeReadyMinutes(fallback);
            if (number.compareTo(BigDecimal.valueOf(5)) < 0) return 5;
            if (number.compareTo(BigDecimal.valueOf(120)) > 0) return 120;
            return number.intValue();
        } catch (NumberFormatException invalid) { return OrderController.normalizeReadyMinutes(fallback); }
    }
}
