package com.lordgainsborough.restaurant.service;
import com.lordgainsborough.restaurant.model.Reservation;
import java.util.Map;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaReservation;
public class ReservationMapper {
    public static void validate(Reservation source) {
        if (source==null) PostgresInput.invalid();
        PostgresInput.text(source.getName(),255,true); PostgresInput.text(source.getEmail(),255,true);
        PostgresInput.text(source.getPhone(),50,true); PostgresInput.text(source.getDate(),20,true); PostgresInput.text(source.getTime(),20,true);
        PostgresInput.text(source.getSpecialRequests(),Integer.MAX_VALUE,false);
        if (source.getPartySize()<=0) PostgresInput.invalid();
    }
    public JpaReservation toJpa(Reservation source) {
        validate(source);
        var row=new JpaReservation(source.getName(),source.getEmail(),source.getPhone(),source.getDate(),source.getTime(),source.getPartySize());
        row.setSpecialRequests(source.getSpecialRequests()); return row;
    }
    public Reservation fromJpa(JpaReservation source) {
        var dto=new Reservation(); dto.setId(source.getId()); dto.setName(source.getName()); dto.setEmail(source.getEmail()); dto.setPhone(source.getPhone());
        dto.setDate(source.getDate()); dto.setTime(source.getTime()); dto.setPartySize(source.getPartySize()); dto.setSpecialRequests(source.getSpecialRequests());
        dto.setStatus(source.getStatus()); dto.setCreatedAt(source.getCreatedAt()); return dto;
    }
    public static void validateStatus(Map<String,String> body) {
        if (body.containsKey("status")) {
            // Arbitrary strings, including empty strings, remain supported; SQL NULL is incompatible.
            if (body.get("status")==null) PostgresInput.invalid();
            PostgresInput.text(body.get("status"),50,false);
        }
    }
}
