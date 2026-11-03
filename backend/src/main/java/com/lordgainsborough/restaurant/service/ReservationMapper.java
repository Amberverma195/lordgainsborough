package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.Reservation;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaReservation;
import com.lordgainsborough.restaurant.util.EmailValidator;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Map;
import java.util.regex.Pattern;

public class ReservationMapper {

    public static final ZoneId RESTAURANT_ZONE = ZoneId.of("America/Toronto");
    public static final int MIN_PARTY_SIZE = 1;
    public static final int MAX_PARTY_SIZE = 10;
    public static final int MAX_SPECIAL_REQUESTS_LENGTH = 1000;

    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private static final DateTimeFormatter STRICT_DATE_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);

    private final Clock clock;

    public ReservationMapper() {
        this(Clock.system(RESTAURANT_ZONE));
    }

    public ReservationMapper(Clock clock) {
        this.clock = clock != null ? clock : Clock.system(RESTAURANT_ZONE);
    }

    public static void validate(Reservation source) {
        validate(source, Clock.system(RESTAURANT_ZONE));
    }

    public static void validate(Reservation source, Clock clock) {
        if (source == null) PostgresInput.invalid();

        PostgresInput.text(source.getName(), 255, true);
        PostgresInput.text(source.getEmail(), 255, true);
        if (!EmailValidator.isValidEmail(source.getEmail())) {
            PostgresInput.invalid();
        }

        PostgresInput.text(source.getPhone(), 50, true);
        String digits = source.getPhone().replaceAll("\\D", "");
        if (digits.length() < 7 || digits.length() > 15) {
            PostgresInput.invalid();
        }

        // Party size validation: Reasonable party size limit between 1 and 10 guests
        if (source.getPartySize() < MIN_PARTY_SIZE || source.getPartySize() > MAX_PARTY_SIZE) {
            PostgresInput.invalid();
        }

        // Bounded message length for special requests
        PostgresInput.text(source.getSpecialRequests(), MAX_SPECIAL_REQUESTS_LENGTH, false);

        // Date validation: Valid calendar date, format YYYY-MM-DD, in future / not past, within 1 year
        PostgresInput.text(source.getDate(), 20, true);
        if (!DATE_PATTERN.matcher(source.getDate().trim()).matches()) {
            PostgresInput.invalid();
        }

        LocalDate resDate;
        try {
            resDate = LocalDate.parse(source.getDate().trim(), STRICT_DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            PostgresInput.invalid();
            return;
        }

        Clock effectiveClock = clock != null ? clock.withZone(RESTAURANT_ZONE) : Clock.system(RESTAURANT_ZONE);
        LocalDate today = LocalDate.now(effectiveClock);

        if (resDate.isBefore(today)) {
            PostgresInput.invalid();
        }
        if (resDate.isAfter(today.plusYears(1))) {
            PostgresInput.invalid();
        }

        // Time validation: Valid time, format HH:mm, 30-minute intervals, within opening hours
        PostgresInput.text(source.getTime(), 20, true);
        if (!TIME_PATTERN.matcher(source.getTime().trim()).matches()) {
            PostgresInput.invalid();
        }

        LocalTime resTime;
        try {
            resTime = LocalTime.parse(source.getTime().trim(), DateTimeFormatter.ofPattern("HH:mm"));
        } catch (DateTimeParseException e) {
            PostgresInput.invalid();
            return;
        }

        // Permitted time slots: strictly 30-minute steps (:00 or :30)
        if (resTime.getMinute() != 0 && resTime.getMinute() != 30) {
            PostgresInput.invalid();
        }

        // Within opening hours for restaurant in London, Ontario (Eastern Time)
        // Sunday: 10:00 - 18:00
        // Friday & Saturday: 09:00 - 20:30
        // Monday through Thursday: 09:00 - 20:00
        DayOfWeek dayOfWeek = resDate.getDayOfWeek();
        LocalTime openTime;
        LocalTime closeTime;
        if (dayOfWeek == DayOfWeek.SUNDAY) {
            openTime = LocalTime.of(10, 0);
            closeTime = LocalTime.of(18, 0);
        } else if (dayOfWeek == DayOfWeek.FRIDAY || dayOfWeek == DayOfWeek.SATURDAY) {
            openTime = LocalTime.of(9, 0);
            closeTime = LocalTime.of(20, 30);
        } else {
            openTime = LocalTime.of(9, 0);
            closeTime = LocalTime.of(20, 0);
        }

        if (resTime.isBefore(openTime) || resTime.isAfter(closeTime)) {
            PostgresInput.invalid();
        }

        // If reservation is for today, time slot must be strictly in the future
        if (resDate.equals(today)) {
            LocalTime nowTime = LocalTime.now(effectiveClock);
            if (!resTime.isAfter(nowTime)) {
                PostgresInput.invalid();
            }
        }
    }

    public JpaReservation toJpa(Reservation source) {
        validate(source, clock);
        var row = new JpaReservation(source.getName(), source.getEmail(), source.getPhone(), source.getDate(), source.getTime(), source.getPartySize());
        row.setSpecialRequests(source.getSpecialRequests());
        return row;
    }

    public Reservation fromJpa(JpaReservation source) {
        var dto = new Reservation();
        dto.setId(source.getId());
        dto.setName(source.getName());
        dto.setEmail(source.getEmail());
        dto.setPhone(source.getPhone());
        dto.setDate(source.getDate());
        dto.setTime(source.getTime());
        dto.setPartySize(source.getPartySize());
        dto.setSpecialRequests(source.getSpecialRequests());
        dto.setStatus(source.getStatus());
        dto.setCreatedAt(source.getCreatedAt());
        return dto;
    }

    public static void validateStatus(Map<String, String> body) {
        if (body.containsKey("status")) {
            // Arbitrary strings, including empty strings, remain supported; SQL NULL is incompatible.
            if (body.get("status") == null) PostgresInput.invalid();
            PostgresInput.text(body.get("status"), 50, false);
        }
    }
}
