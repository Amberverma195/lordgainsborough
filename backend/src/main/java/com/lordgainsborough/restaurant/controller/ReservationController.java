package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.model.Reservation;
import com.lordgainsborough.restaurant.security.ClientIpResolver;
import com.lordgainsborough.restaurant.service.ReservationRateLimiter;
import com.lordgainsborough.restaurant.service.ReservationStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationStore store;
    private final ReservationRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    @Autowired
    public ReservationController(
            ReservationStore store,
            @Autowired(required = false) ReservationRateLimiter rateLimiter,
            @Autowired(required = false) ClientIpResolver clientIpResolver) {
        this.store = store;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    public ReservationStore getReservationStore() { return store; }

    public ReservationRateLimiter getReservationRateLimiter() { return rateLimiter; }

    public ClientIpResolver getClientIpResolver() { return clientIpResolver; }

    @GetMapping
    public List<Reservation> getAllReservations() { return store.all(); }

    @PostMapping
    public Reservation createReservation(
            @RequestBody Reservation request,
            @Autowired(required = false) HttpServletRequest servletRequest,
            @Autowired(required = false) HttpServletResponse servletResponse) {
        if (rateLimiter != null && clientIpResolver != null && servletRequest != null) {
            String clientIp = clientIpResolver.resolve(
                    servletRequest.getRemoteAddr(),
                    servletRequest.getHeader("X-Forwarded-For"),
                    servletRequest.getHeader("X-Real-IP"));
            OptionalLong retryAfter = rateLimiter.consumeIpRequest(clientIp);
            if (retryAfter.isPresent()) {
                if (servletResponse != null) {
                    servletResponse.addHeader("Retry-After", String.valueOf(retryAfter.getAsLong()));
                }
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many reservation requests. Please try again later.");
            }
        }
        return store.create(request);
    }

    public Reservation createReservation(Reservation request) {
        return createReservation(request, null, null);
    }

    @DeleteMapping("/{id}")
    public void deleteReservation(@PathVariable String id) { store.delete(id); }

    @PatchMapping("/{id}/status")
    public Reservation updateReservationStatus(@PathVariable String id, @RequestBody Map<String, String> body) {
        return store.status(id, body).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
    }
}
