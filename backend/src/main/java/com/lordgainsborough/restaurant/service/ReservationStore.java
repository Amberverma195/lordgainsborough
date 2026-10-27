package com.lordgainsborough.restaurant.service;
import com.lordgainsborough.restaurant.model.Reservation;
import java.util.*;
public interface ReservationStore {
    List<Reservation> all();
    Reservation create(Reservation request);
    void delete(String id);
    Optional<Reservation> status(String id, Map<String,String> body);
}
