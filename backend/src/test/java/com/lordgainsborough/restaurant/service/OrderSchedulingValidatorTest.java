package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrderSchedulingValidatorTest {

    private static final ZoneId TORONTO_ZONE = ZoneId.of("America/Toronto");
    // Wednesday Nov 4, 2026 at 14:00 (2:00 PM) in America/Toronto (EST, UTC-5)
    private static final ZonedDateTime FIXED_NOW = ZonedDateTime.of(2026, 11, 4, 14, 0, 0, 0, TORONTO_ZONE);

    private Clock clock;
    private OrderSchedulingValidator validator;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_NOW.toInstant(), TORONTO_ZONE);
        validator = new OrderSchedulingValidator(clock);
    }

    private CustomerOrder order(String date, String time) {
        CustomerOrder o = new CustomerOrder();
        o.setPickupDate(date);
        o.setPickupTime(time);
        return o;
    }

    @Test
    @DisplayName("Blank pickup date defaults to today and blank time defaults to asap during operating hours")
    void testBlankDateAndTimeToDefaults() {
        CustomerOrder o = order("", "");
        Map<String, String> error = validator.validate(o);

        assertThat(error).isNull();
        assertThat(o.getPickupDate()).isEqualTo("2026-11-04");
        assertThat(o.getPickupTime()).isEqualTo("asap");
    }

    @Test
    @DisplayName("Explicit 'asap' option (case-insensitive) is supported during operating hours")
    void testAsapOptionSupported() {
        CustomerOrder o1 = order("2026-11-04", "asap");
        assertThat(validator.validate(o1)).isNull();
        assertThat(o1.getPickupTime()).isEqualTo("asap");

        CustomerOrder o2 = order("2026-11-04", "ASAP");
        assertThat(validator.validate(o2)).isNull();
        assertThat(o2.getPickupTime()).isEqualTo("asap");

        CustomerOrder o3 = order("2026-11-04", "  Asap  ");
        assertThat(validator.validate(o3)).isNull();
        assertThat(o3.getPickupTime()).isEqualTo("asap");
    }

    @Test
    @DisplayName("ASAP order placed after closing time (9:00 PM) for today is rejected")
    void testAsapAfterClosingTimeRejected() {
        ZonedDateTime lateNow = ZonedDateTime.of(2026, 11, 4, 21, 15, 0, 0, TORONTO_ZONE);
        OrderSchedulingValidator lateValidator = new OrderSchedulingValidator(Clock.fixed(lateNow.toInstant(), TORONTO_ZONE));

        CustomerOrder o = order("2026-11-04", "asap");
        Map<String, String> error = lateValidator.validate(o);

        assertThat(error).isNotNull();
        assertThat(error.get("code")).isEqualTo("PICKUP_TIME_CLOSED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"11:00 AM", "11:30 AM", "12:00 PM", "12:30 PM", "1:00 PM", "1:30 PM", "2:00 PM", "5:30 PM", "8:30 PM", "9:00 PM"})
    @DisplayName("Valid 12-hour AM/PM formats are accepted on a future date")
    void testValid12HourFormatsFutureDate(String time) {
        CustomerOrder o = order("2026-11-05", time);
        assertThat(validator.validate(o)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"11:00", "11:30", "12:00", "12:30", "13:00", "13:30", "17:30", "20:30", "21:00"})
    @DisplayName("Valid 24-hour formats are accepted on a future date")
    void testValid24HourFormatsFutureDate(String time) {
        CustomerOrder o = order("2026-11-05", time);
        assertThat(validator.validate(o)).isNull();
    }

    @Test
    @DisplayName("Past times for today (before or at 14:00) are rejected with PAST_PICKUP_TIME")
    void testPastTimesTodayRejected() {
        // At 14:00 (2:00 PM) today, 11:00 AM, 1:30 PM, 2:00 PM are in the past
        for (String pastTime : new String[]{"11:00 AM", "11:30 AM", "12:00 PM", "1:00 PM", "1:30 PM", "2:00 PM", "11:00", "13:30", "14:00"}) {
            CustomerOrder o = order("2026-11-04", pastTime);
            Map<String, String> error = validator.validate(o);
            assertThat(error).as("Time %s should be rejected as past time", pastTime).isNotNull();
            assertThat(error.get("code")).isEqualTo("PAST_PICKUP_TIME");
        }
    }

    @Test
    @DisplayName("Future times for today (after 14:00) are accepted")
    void testFutureTimesTodayAccepted() {
        // 2:30 PM, 3:00 PM, 6:00 PM, 9:00 PM are future times
        for (String futureTime : new String[]{"2:30 PM", "3:00 PM", "6:00 PM", "9:00 PM", "14:30", "18:00", "21:00"}) {
            CustomerOrder o = order("2026-11-04", futureTime);
            Map<String, String> error = validator.validate(o);
            assertThat(error).as("Time %s should be accepted", futureTime).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-03", "2026-11-01", "2020-01-01"})
    @DisplayName("Past pickup dates are rejected with INVALID_PICKUP_DATE")
    void testPastPickupDateRejected(String pastDate) {
        CustomerOrder o = order(pastDate, "18:00");
        Map<String, String> error = validator.validate(o);
        assertThat(error).isNotNull();
        assertThat(error.get("code")).isEqualTo("INVALID_PICKUP_DATE");
        assertThat(error.get("message")).contains("past");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-02-31", "2026-13-01", "2026-04-31", "invalid-date", "Sat Nov 1", "tomorrow"})
    @DisplayName("Invalid or unparseable calendar dates are rejected with INVALID_PICKUP_DATE")
    void testInvalidDatesRejected(String invalidDate) {
        CustomerOrder o = order(invalidDate, "18:00");
        Map<String, String> error = validator.validate(o);
        assertThat(error).isNotNull();
        assertThat(error.get("code")).isEqualTo("INVALID_PICKUP_DATE");
    }

    @Test
    @DisplayName("Dates beyond 90 days in advance are rejected")
    void testTooFarInAdvanceDateRejected() {
        LocalDate farFuture = LocalDate.of(2026, 11, 4).plusDays(91);
        CustomerOrder o = order(farFuture.toString(), "18:00");
        Map<String, String> error = validator.validate(o);
        assertThat(error).isNotNull();
        assertThat(error.get("code")).isEqualTo("INVALID_PICKUP_DATE");
        assertThat(error.get("message")).contains("90 days");
    }

    @ParameterizedTest
    @ValueSource(strings = {"10:30 AM", "10:00 AM", "9:00 AM", "12:00 AM", "09:00", "10:30"})
    @DisplayName("Times before opening hour (11:00 AM) are rejected with INVALID_PICKUP_TIME")
    void testTimesBeforeOpeningRejected(String time) {
        CustomerOrder o = order("2026-11-05", time);
        Map<String, String> error = validator.validate(o);
        assertThat(error).isNotNull();
        assertThat(error.get("code")).isEqualTo("INVALID_PICKUP_TIME");
        assertThat(error.get("message")).contains("between 11:00 AM and 9:00 PM");
    }

    @ParameterizedTest
    @ValueSource(strings = {"9:30 PM", "10:00 PM", "11:00 PM", "21:30", "22:00", "23:00"})
    @DisplayName("Times after closing hour (9:00 PM) are rejected with INVALID_PICKUP_TIME")
    void testTimesAfterClosingRejected(String time) {
        CustomerOrder o = order("2026-11-05", time);
        Map<String, String> error = validator.validate(o);
        assertThat(error).isNotNull();
        assertThat(error.get("code")).isEqualTo("INVALID_PICKUP_TIME");
        assertThat(error.get("message")).contains("between 11:00 AM and 9:00 PM");
    }

    @ParameterizedTest
    @ValueSource(strings = {"11:15 AM", "12:20 PM", "1:45 PM", "14:15", "18:45", "19:05"})
    @DisplayName("Non-30-minute intervals are rejected with INVALID_PICKUP_TIME")
    void testNon30MinuteIntervalsRejected(String time) {
        CustomerOrder o = order("2026-11-05", time);
        Map<String, String> error = validator.validate(o);
        assertThat(error).isNotNull();
        assertThat(error.get("code")).isEqualTo("INVALID_PICKUP_TIME");
        assertThat(error.get("message")).contains("30-minute intervals");
    }

    @ParameterizedTest
    @ValueSource(strings = {"midnight", "noon", "random-time", "25:00", "14:99"})
    @DisplayName("Malformed time strings are rejected with INVALID_PICKUP_TIME")
    void testMalformedTimeStringsRejected(String time) {
        CustomerOrder o = order("2026-11-05", time);
        Map<String, String> error = validator.validate(o);
        assertThat(error).isNotNull();
        assertThat(error.get("code")).isEqualTo("INVALID_PICKUP_TIME");
    }
}
