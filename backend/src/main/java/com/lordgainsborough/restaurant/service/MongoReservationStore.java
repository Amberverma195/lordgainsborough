package com.lordgainsborough.restaurant.service;
import com.lordgainsborough.restaurant.model.Reservation;
import com.lordgainsborough.restaurant.repository.ReservationRepository;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.Sort;
import java.time.LocalDateTime;
import java.util.*;
@Component
public class MongoReservationStore implements ReservationStore {
    private final ReservationRepository repository;
    public MongoReservationStore(ReservationRepository repository) { this.repository=repository; }
    public List<Reservation> all() { return repository.findAll(Sort.by(Sort.Direction.DESC,"createdAt")); }
    public Reservation create(Reservation request) {
        ReservationMapper.validate(request); request.setId(null); request.setStatus("PENDING"); request.setCreatedAt(LocalDateTime.now());
        return repository.save(request);
    }
    public void delete(String id) { repository.deleteById(id); }
    public Optional<Reservation> status(String id,Map<String,String> body) {
        return repository.findById(id).map(row -> {
            if (body.containsKey("status")) { ReservationMapper.validateStatus(body); row.setStatus(body.get("status")); return repository.save(row); }
            return row;
        });
    }
}
