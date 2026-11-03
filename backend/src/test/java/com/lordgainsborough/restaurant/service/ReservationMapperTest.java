package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.Reservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationMapperTest {

    // Fixed instant: Wednesday 2026-11-04 12:00:00 in America/Toronto
    private static final ZoneId ZONE = ZoneId.of("America/Toronto");
    private static final ZonedDateTime FIXED_NOW = ZonedDateTime.of(2026, 11, 4, 12, 0, 0, 0, ZONE);
    private final Clock clock = Clock.fixed(FIXED_NOW.toInstant(), ZONE);
    private ReservationMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ReservationMapper(clock);
    }

    private Reservation validReservation() {
        Reservation r = new Reservation();
        r.setName("Lord Gainsborough");
        r.setEmail("guest@example.com");
        r.setPhone("519-555-1234");
        r.setDate("2026-11-05"); // Thursday, tomorrow
        r.setTime("18:00");       // Within 09:00 - 20:00 on Thursday
        r.setPartySize(4);
        r.setSpecialRequests("Window seat please");
        return r;
    }

    @Test
    @DisplayName("Valid reservation maps to JPA entity and back")
    void testValidReservationMapping() {
        Reservation r = validReservation();
        var jpa = mapper.toJpa(r);
        assertThat(jpa.getName()).isEqualTo(r.getName());
        assertThat(jpa.getEmail()).isEqualTo(r.getEmail());
        assertThat(jpa.getPhone()).isEqualTo(r.getPhone());
        assertThat(jpa.getDate()).isEqualTo(r.getDate());
        assertThat(jpa.getTime()).isEqualTo(r.getTime());
        assertThat(jpa.getPartySize()).isEqualTo(4);
        assertThat(jpa.getSpecialRequests()).isEqualTo(r.getSpecialRequests());

        var back = mapper.fromJpa(jpa);
        assertThat(back.getName()).isEqualTo(r.getName());
        assertThat(back.getDate()).isEqualTo(r.getDate());
        assertThat(back.getTime()).isEqualTo(r.getTime());
        assertThat(back.getPartySize()).isEqualTo(4);
    }

    @Test
    @DisplayName("Null reservation is rejected")
    void testNullReservation() {
        assertThatThrownBy(() -> mapper.toJpa(null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   "})
    @DisplayName("Blank contact fields are rejected")
    void testBlankContactFields(String blank) {
        Reservation r1 = validReservation();
        r1.setName(blank);
        assertThatThrownBy(() -> mapper.toJpa(r1)).isInstanceOf(ResponseStatusException.class);

        Reservation r2 = validReservation();
        r2.setEmail(blank);
        assertThatThrownBy(() -> mapper.toJpa(r2)).isInstanceOf(ResponseStatusException.class);

        Reservation r3 = validReservation();
        r3.setPhone(blank);
        assertThatThrownBy(() -> mapper.toJpa(r3)).isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "@example.com", "user@", "user@domain", "user@.com"})
    @DisplayName("Malformed email addresses are rejected")
    void testInvalidEmails(String invalidEmail) {
        Reservation r = validReservation();
        r.setEmail(invalidEmail);
        assertThatThrownBy(() -> mapper.toJpa(r)).isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "abc", "phone", "55-12"})
    @DisplayName("Phones with insufficient digits are rejected")
    void testInvalidPhones(String invalidPhone) {
        Reservation r = validReservation();
        r.setPhone(invalidPhone);
        assertThatThrownBy(() -> mapper.toJpa(r)).isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -5, 11, 12, 100, 10000})
    @DisplayName("Party size outside 1..10 is rejected")
    void testPartySizeBounds(int invalidPartySize) {
        Reservation r = validReservation();
        r.setPartySize(invalidPartySize);
        assertThatThrownBy(() -> mapper.toJpa(r)).isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 8, 10})
    @DisplayName("Party size within 1..10 is accepted")
    void testValidPartySizes(int validPartySize) {
        Reservation r = validReservation();
        r.setPartySize(validPartySize);
        var jpa = mapper.toJpa(r);
        assertThat(jpa.getPartySize()).isEqualTo(validPartySize);
    }

    @Test
    @DisplayName("Special requests message length is bounded to 1000 characters")
    void testSpecialRequestsLength() {
        Reservation rValid = validReservation();
        rValid.setSpecialRequests("A".repeat(1000));
        assertThat(mapper.toJpa(rValid).getSpecialRequests()).hasSize(1000);

        Reservation rInvalid = validReservation();
        rInvalid.setSpecialRequests("A".repeat(1001));
        assertThatThrownBy(() -> mapper.toJpa(rInvalid)).isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Sat Nov 1", "2026/11/05", "11-05-2026", "tomorrow", "2026-11-5"})
    @DisplayName("Invalid date formats are rejected")
    void testInvalidDateFormats(String invalidDate) {
        Reservation r = validReservation();
        r.setDate(invalidDate);
        assertThatThrownBy(() -> mapper.toJpa(r)).isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-02-30", "2026-02-31", "2026-11-31", "2026-13-01", "2026-00-10"})
    @DisplayName("Non-existent calendar dates are strictly rejected")
    void testNonExistentCalendarDates(String invalidCalendarDate) {
        Reservation r = validReservation();
        r.setDate(invalidCalendarDate);
        assertThatThrownBy(() -> mapper.toJpa(r)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("Past reservation date is rejected")
    void testPastReservationDate() {
        Reservation r = validReservation();
        r.setDate("2026-11-03"); // Yesterday relative to fixed Wednesday 2026-11-04
        assertThatThrownBy(() -> mapper.toJpa(r)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("Date more than 1 year in the future is rejected")
    void testFarFutureReservationDate() {
        Reservation r = validReservation();
        r.setDate("2027-11-10"); // More than 1 year from 2026-11-04
        assertThatThrownBy(() -> mapper.toJpa(r)).isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"7 PM", "18:00:00", "6pm", "25:00", "12:60", "invalid"})
    @DisplayName("Invalid time formats are rejected")
    void testInvalidTimeFormats(String invalidTime) {
        Reservation r = validReservation();
        r.setTime(invalidTime);
        assertThatThrownBy(() -> mapper.toJpa(r)).isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12:15", "12:45", "18:05", "18:25", "19:59"})
    @DisplayName("Times that are not 30-minute intervals are rejected")
    void testNon30MinuteTimeSlots(String non30MinTime) {
        Reservation r = validReservation();
        r.setTime(non30MinTime);
        assertThatThrownBy(() -> mapper.toJpa(r)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("Sunday opening hours: 10:00 to 18:00")
    void testSundayOpeningHours() {
        Reservation r = validReservation();
        r.setDate("2026-11-08"); // Sunday

        // Before 10:00 -> rejected
        r.setTime("09:30");
        assertThatThrownBy(() -> mapper.toJpa(r)).isInstanceOf(ResponseStatusException.class);

        // After 18:00 -> rejected
        r.setTime("18:30");
        assertThatThrownBy(() -> mapper.toJpa(r)).isInstanceOf(ResponseStatusException.class);

        // 10:00, 14:00, 18:00 -> accepted
        r.setTime("10:00");
        assertThat(mapper.toJpa(r).getTime()).isEqualTo("10:00");

        r.setTime("14:30");
        assertThat(mapper.toJpa(r).getTime()).isEqualTo("14:30");

        r.setTime("18:00");
        assertThat(mapper.toJpa(r).getTime()).isEqualTo("18:00");
    }

    @Test
    @DisplayName("Monday through Thursday opening hours: 09:00 to 20:00")
    void testMonToThuOpeningHours() {
        Reservation r = validReservation();
        r.setDate("2026-11-05"); // Thursday

        // Before 09:00 -> rejected
        r.setTime("08:30");
        assertThatThrownBy(() -> mapper.toJpa(r)).isInstanceOf(ResponseStatusException.class);

        // After 20:00 -> rejected
        r.setTime("20:30");
        assertThatThrownBy(() -> mapper.toJpa(r)).isInstanceOf(ResponseStatusException.class);

        // 09:00 and 20:00 -> accepted
        r.setTime("09:00");
        assertThat(mapper.toJpa(r).getTime()).isEqualTo("09:00");

        r.setTime("20:00");
        assertThat(mapper.toJpa(r).getTime()).isEqualTo("20:00");
    }

    @Test
    @DisplayName("Friday and Saturday opening hours: 09:00 to 20:30")
    void testFridaySaturdayOpeningHours() {
        Reservation r = validReservation();
        r.setDate("2026-11-06"); // Friday

        // Before 09:00 -> rejected
        r.setTime("08:30");
        assertThatThrownBy(() -> mapper.toJpa(r)).isInstanceOf(ResponseStatusException.class);

        // After 20:30 -> rejected
        r.setTime("21:00");
        assertThatThrownBy(() -> mapper.toJpa(r)).isInstanceOf(ResponseStatusException.class);

        // 20:30 -> accepted on Friday
        r.setTime("20:30");
        assertThat(mapper.toJpa(r).getTime()).isEqualTo("20:30");
    }

    @Test
    @DisplayName("Today booking: time slot in the past is rejected, future slot is accepted")
    void testTodayBookingTimeSlot() {
        Reservation r = validReservation();
        r.setDate("2026-11-04"); // Today (at 12:00 in clock)

        // 11:30 is in the past today -> rejected
        r.setTime("11:30");
        assertThatThrownBy(() -> mapper.toJpa(r)).isInstanceOf(ResponseStatusException.class);

        // 12:00 is not strictly after now (12:00) -> rejected
        r.setTime("12:00");
        assertThatThrownBy(() -> mapper.toJpa(r)).isInstanceOf(ResponseStatusException.class);

        // 12:30 is in the future today -> accepted
        r.setTime("12:30");
        assertThat(mapper.toJpa(r).getTime()).isEqualTo("12:30");

        // 19:00 is in the future today -> accepted
        r.setTime("19:00");
        assertThat(mapper.toJpa(r).getTime()).isEqualTo("19:00");
    }

    @Test
    @DisplayName("validateStatus allows status update but rejects null status")
    void testValidateStatus() {
        ReservationMapper.validateStatus(Map.of("status", "CONFIRMED"));
        ReservationMapper.validateStatus(Map.of("status", "CANCELLED"));
        ReservationMapper.validateStatus(Map.of("other", "value"));

        assertThatThrownBy(() -> {
            Map<String, String> body = new java.util.HashMap<>();
            body.put("status", null);
            ReservationMapper.validateStatus(body);
        }).isInstanceOf(ResponseStatusException.class);
    }
}
