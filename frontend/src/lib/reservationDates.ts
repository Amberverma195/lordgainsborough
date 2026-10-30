/**
 * Utilities for restaurant reservations in London, Ontario (Eastern Time / America/Toronto).
 *
 * Avoids JavaScript's Date parsing timezone gotcha where date-only strings (e.g. "2026-11-01")
 * are parsed as UTC midnight, which in Canadian time zones shifts to the previous evening locally
 * and returns the wrong day of the week.
 */

export const RESTAURANT_TIMEZONE = 'America/Toronto';

export interface OperatingHours {
  startHour: number;
  startMinutes: number;
  endHour: number;
  endMinutes: number;
}

/**
 * Returns the calendar day of the week (0 = Sunday, 1 = Monday, ..., 6 = Saturday)
 * for a "YYYY-MM-DD" date string without UTC timezone shifting.
 */
export function getCalendarDayOfWeek(dateStr: string): number {
  if (!dateStr || typeof dateStr !== 'string') return -1;
  const parts = dateStr.trim().split('-');
  if (parts.length !== 3) return -1;
  const year = parseInt(parts[0], 10);
  const month = parseInt(parts[1], 10);
  const day = parseInt(parts[2], 10);
  if (isNaN(year) || isNaN(month) || isNaN(day) || month < 1 || month > 12 || day < 1 || day > 31) {
    return -1;
  }
  // Construct date at local noon to completely prevent any boundary or daylight-saving shift
  const dateObj = new Date(year, month - 1, day, 12, 0, 0);
  return dateObj.getDay();
}

/**
 * Returns the current calendar date in London, Ontario (America/Toronto) in "YYYY-MM-DD" format.
 * Used for the date input's `min` attribute so evening bookings use local Canadian time instead of UTC.
 */
export function getRestaurantTodayDateString(referenceDate: Date = new Date()): string {
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone: RESTAURANT_TIMEZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  });
  return formatter.format(referenceDate);
}

/**
 * Returns current date string, hour, and minute in London, Ontario (America/Toronto).
 */
export function getRestaurantNowParts(referenceDate: Date = new Date()): {
  dateString: string;
  hour: number;
  minute: number;
} {
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone: RESTAURANT_TIMEZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: 'numeric',
    minute: 'numeric',
    hour12: false,
  });

  const parts = formatter.formatToParts(referenceDate);
  let year = '', month = '', day = '', hour = '0', minute = '0';
  for (const p of parts) {
    if (p.type === 'year') year = p.value;
    if (p.type === 'month') month = p.value;
    if (p.type === 'day') day = p.value;
    if (p.type === 'hour') hour = p.value;
    if (p.type === 'minute') minute = p.value;
  }

  return {
    dateString: `${year}-${month}-${day}`,
    hour: parseInt(hour, 10) % 24,
    minute: parseInt(minute, 10),
  };
}

/**
 * Returns operating hours for a given day of week:
 * - Sunday (0): 10:00 AM - 6:00 PM (10:00 - 18:00)
 * - Friday (5) & Saturday (6): 9:00 AM - 8:30 PM (09:00 - 20:30)
 * - Monday (1) through Thursday (4): 9:00 AM - 8:00 PM (09:00 - 20:00)
 */
export function getOperatingHours(dayOfWeek: number): OperatingHours {
  if (dayOfWeek === 0) {
    // Sunday: 10 AM - 6 PM
    return { startHour: 10, startMinutes: 0, endHour: 18, endMinutes: 0 };
  }
  if (dayOfWeek === 5 || dayOfWeek === 6) {
    // Fri, Sat: 9 AM - 8:30 PM
    return { startHour: 9, startMinutes: 0, endHour: 20, endMinutes: 30 };
  }
  // Mon - Thu: 9 AM - 8 PM
  return { startHour: 9, startMinutes: 0, endHour: 20, endMinutes: 0 };
}

/**
 * Generates available 30-minute reservation times for a given "YYYY-MM-DD" date string.
 * Past time slots for today are filtered out using the restaurant's local time.
 */
export function getAvailableTimesForDate(
  dateStr: string,
  referenceDate: Date = new Date()
): Array<{ value: string; label: string }> {
  if (!dateStr) return [];

  const dayOfWeek = getCalendarDayOfWeek(dateStr);
  if (dayOfWeek === -1) return [];

  const hours = getOperatingHours(dayOfWeek);
  const nowInRestaurant = getRestaurantNowParts(referenceDate);
  const isToday = dateStr === nowInRestaurant.dateString;

  const times: Array<{ value: string; label: string }> = [];

  for (let hour = hours.startHour; hour <= hours.endHour; hour++) {
    for (const min of [0, 30]) {
      // Don't go before start time (e.g. 10:30 on Sunday is ok, but not 09:30)
      if (hour === hours.startHour && min < hours.startMinutes) continue;
      // Don't go past closing time (e.g. 20:30 on Fri/Sat, or 18:00 on Sun)
      if (hour === hours.endHour && min > hours.endMinutes) break;

      // Filter past time slots if booking for today
      if (isToday) {
        if (hour < nowInRestaurant.hour || (hour === nowInRestaurant.hour && min <= nowInRestaurant.minute)) {
          continue;
        }
      }

      const period = hour >= 12 ? 'PM' : 'AM';
      const displayHour = hour > 12 ? hour - 12 : (hour === 0 ? 12 : hour);
      const timeStr = `${hour.toString().padStart(2, '0')}:${min.toString().padStart(2, '0')}`;
      const displayStr = `${displayHour}:${min.toString().padStart(2, '0')} ${period}`;

      times.push({ value: timeStr, label: displayStr });
    }
  }

  return times;
}

/**
 * Validates whether a selected date and time represent an acceptable booking window.
 */
export function validateReservationDateTime(
  dateStr: string,
  timeStr: string,
  referenceDate: Date = new Date()
): { valid: boolean; reason?: string } {
  if (!dateStr || !timeStr) {
    return { valid: false, reason: 'Date and time are required.' };
  }

  const todayStr = getRestaurantTodayDateString(referenceDate);
  if (dateStr < todayStr) {
    return { valid: false, reason: 'Reservation date cannot be in the past.' };
  }

  const availableSlots = getAvailableTimesForDate(dateStr, referenceDate);
  const isAvailable = availableSlots.some((slot) => slot.value === timeStr);

  if (!isAvailable) {
    const day = getCalendarDayOfWeek(dateStr);
    const dayNames = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
    const dayName = day >= 0 && day <= 6 ? dayNames[day] : 'selected date';
    return {
      valid: false,
      reason: `The selected time (${timeStr}) is not available on ${dayName}. Please select an open time slot.`,
    };
  }

  return { valid: true };
}
