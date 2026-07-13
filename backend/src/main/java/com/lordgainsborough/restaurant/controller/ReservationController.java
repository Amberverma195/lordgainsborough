package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.model.Reservation;
import com.lordgainsborough.restaurant.repository.ReservationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/reservations")
@CrossOrigin(origins = "*")
public class ReservationController {

    @Autowired
    private ReservationRepository reservationRepository;

    @GetMapping
    public List<Reservation> getAllReservations() {
        return reservationRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @PostMapping
    public Reservation createReservation(@RequestBody Reservation reservation) {
        // Public endpoint: never trust client-supplied id/status/createdAt.
        reservation.setId(null);
        reservation.setStatus("PENDING");
        reservation.setCreatedAt(LocalDateTime.now());
        return reservationRepository.save(reservation);
    }

    @DeleteMapping("/{id}")
    public void deleteReservation(@PathVariable String id) {
        reservationRepository.deleteById(id);
    }

    @PatchMapping("/{id}/status")
    public Reservation updateReservationStatus(@PathVariable String id, @RequestBody java.util.Map<String, String> body) {
        Reservation res = reservationRepository.findById(id).orElseThrow(() -> new RuntimeException("Reservation not found"));
        if (body.containsKey("status")) {
            res.setStatus(body.get("status"));
            return reservationRepository.save(res);
        }
        return res;
    }
}
