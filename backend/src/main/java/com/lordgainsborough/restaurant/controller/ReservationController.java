package com.lordgainsborough.restaurant.controller;
import com.lordgainsborough.restaurant.model.Reservation;
import com.lordgainsborough.restaurant.repository.ReservationRepository;
import com.lordgainsborough.restaurant.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/reservations")
public class ReservationController {
    private final ReservationStore store;
    @Autowired public ReservationController(ReservationStore store) { this.store=store; }
    public ReservationController(ReservationRepository repository) { this(new MongoReservationStore(repository)); }
    public ReservationStore getReservationStore() { return store; }
    @GetMapping public List<Reservation> getAllReservations() { return store.all(); }
    @PostMapping public Reservation createReservation(@RequestBody Reservation request) { return store.create(request); }
    @DeleteMapping("/{id}") public void deleteReservation(@PathVariable String id) { store.delete(id); }
    @PatchMapping("/{id}/status") public Reservation updateReservationStatus(@PathVariable String id,@RequestBody Map<String,String> body) {
        return store.status(id,body).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Reservation not found"));
    }
}
