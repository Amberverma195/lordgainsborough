package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OrderSchedulingValidator {

    public static final ZoneId RESTAURANT_ZONE = ZoneId.of("America/Toronto");
    public static final LocalTime OPENING_TIME = LocalTime.of(11, 0);
    public static final LocalTime CLOSING_TIME = LocalTime.of(21, 0);
    public static final int MAX_DAYS_IN_ADVANCE = 90;

    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final DateTimeFormatter STRICT_DATE_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);

    private static final Pattern TIME_12H_PATTERN = Pattern.compile("^(0?[1-9]|1[0-2]):([0-5]\\d)\\s*(AM|PM|am|pm)$");
    private static final Pattern TIME_24H_PATTERN = Pattern.compile("^([01]?\\d|2[0-3]):([0-5]\\d)$");

    private final Clock clock;

    public OrderSchedulingValidator() {
        this(Clock.system(RESTAURANT_ZONE));
    }

    @Autowired
    public OrderSchedulingValidator(@Autowired(required = false) Clock clock) {
        this.clock = clock != null ? clock : Clock.system(RESTAURANT_ZONE);
    }

    public Clock getClock() {
        return clock;
    }

    /**
     * Validates pickup scheduling for an order against America/Toronto timezone.
     * Returns an error response map with "code" and "message", or null if valid.
     */
    public Map<String, String> validate(CustomerOrder order) {
        if (order == null) {
            return Map.of("code", "INVALID_ORDER", "message", "Order cannot be null.");
        }

        Clock torontoClock = clock.withZone(RESTAURANT_ZONE);
        LocalDate today = LocalDate.now(torontoClock);
        LocalTime nowTime = LocalTime.now(torontoClock);

        // 1. Validate Pickup Date
        String dateStr = order.getPickupDate();
        LocalDate pickupDate;
        if (dateStr == null || dateStr.isBlank()) {
            pickupDate = today;
            order.setPickupDate(today.toString());
        } else {
            String trimmedDate = dateStr.trim();
            if (!DATE_PATTERN.matcher(trimmedDate).matches()) {
                return Map.of(
                        "code", "INVALID_PICKUP_DATE",
                        "message", "Pickup date must be in YYYY-MM-DD format.");
            }
            try {
                pickupDate = LocalDate.parse(trimmedDate, STRICT_DATE_FORMATTER);
            } catch (DateTimeParseException e) {
                return Map.of(
                        "code", "INVALID_PICKUP_DATE",
                        "message", "Pickup date must be a valid calendar date.");
            }

            if (pickupDate.isBefore(today)) {
                return Map.of(
                        "code", "INVALID_PICKUP_DATE",
                        "message", "Pickup date cannot be in the past.");
            }

            if (pickupDate.isAfter(today.plusDays(MAX_DAYS_IN_ADVANCE))) {
                return Map.of(
                        "code", "INVALID_PICKUP_DATE",
                        "message", "Pickup date cannot be more than " + MAX_DAYS_IN_ADVANCE + " days in advance.");
            }
            order.setPickupDate(trimmedDate);
        }

        // 2. Validate Pickup Time
        String timeStr = order.getPickupTime();
        if (timeStr == null || timeStr.isBlank()) {
            order.setPickupTime("asap");
            timeStr = "asap";
        } else {
            timeStr = timeStr.trim();
            order.setPickupTime(timeStr);
        }

        boolean isAsap = timeStr.equalsIgnoreCase("asap");
        if (isAsap) {
            order.setPickupTime("asap");
            if (pickupDate.equals(today)) {
                if (nowTime.isAfter(CLOSING_TIME)) {
                    return Map.of(
                            "code", "PICKUP_TIME_CLOSED",
                            "message", "Pickup orders are closed for today. Operating hours are 11:00 AM to 9:00 PM.");
                }
            }
            return null;
        }

        // Parse scheduled time
        LocalTime requestedTime = parseTime(timeStr);
        if (requestedTime == null) {
            return Map.of(
                    "code", "INVALID_PICKUP_TIME",
                    "message", "Pickup time must be between 11:00 AM and 9:00 PM in 30-minute intervals, or 'asap'.");
        }

        // Check 30-minute interval (:00 or :30)
        if (requestedTime.getMinute() != 0 && requestedTime.getMinute() != 30) {
            return Map.of(
                    "code", "INVALID_PICKUP_TIME",
                    "message", "Pickup time must be in 30-minute intervals (e.g. 11:00, 11:30).");
        }

        // Check operating hours: 11:00 AM to 9:00 PM
        if (requestedTime.isBefore(OPENING_TIME) || requestedTime.isAfter(CLOSING_TIME)) {
            return Map.of(
                    "code", "INVALID_PICKUP_TIME",
                    "message", "Pickup time must be between 11:00 AM and 9:00 PM.");
        }

        // If scheduled for today, requested time cannot be in the past
        if (pickupDate.equals(today)) {
            if (!requestedTime.isAfter(nowTime)) {
                return Map.of(
                        "code", "PAST_PICKUP_TIME",
                        "message", "Pickup time cannot be in the past.");
            }
        }

        return null;
    }

    public static LocalTime parseTime(String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        Matcher m12 = TIME_12H_PATTERN.matcher(trimmed);
        if (m12.matches()) {
            int hour = Integer.parseInt(m12.group(1));
            int minute = Integer.parseInt(m12.group(2));
            String ampm = m12.group(3).toUpperCase(Locale.ROOT);
            if ("PM".equals(ampm) && hour < 12) {
                hour += 12;
            } else if ("AM".equals(ampm) && hour == 12) {
                hour = 0;
            }
            return LocalTime.of(hour, minute);
        }
        Matcher m24 = TIME_24H_PATTERN.matcher(trimmed);
        if (m24.matches()) {
            int hour = Integer.parseInt(m24.group(1));
            int minute = Integer.parseInt(m24.group(2));
            return LocalTime.of(hour, minute);
        }
        return null;
    }
}
