import {
  getRestaurantTodayDateString,
  getRestaurantNowParts,
} from './reservationDates';

export const PICKUP_DATE_STORAGE_KEY = 'pickupDate';
export const PICKUP_TIME_STORAGE_KEY = 'pickupTime';

export const OPENING_HOUR = 11;
export const CLOSING_HOUR = 21; // 9:00 PM
export const MAX_PICKUP_ADVANCE_DAYS = 90;

/**
 * Generates available 30-minute pickup time slots from 11:00 AM to 9:00 PM.
 */
export function generatePickupTimeSlots(): string[] {
  const slots: string[] = [];
  for (let hour = OPENING_HOUR; hour <= CLOSING_HOUR; hour++) {
    for (const minute of [0, 30]) {
      if (hour === CLOSING_HOUR && minute === 30) break;
      const displayHour = hour > 12 ? hour - 12 : hour;
      const ampm = hour >= 12 ? 'PM' : 'AM';
      const minStr = String(minute).padStart(2, '0');
      slots.push(`${displayHour}:${minStr} ${ampm}`);
    }
  }
  return slots;
}

/**
 * Parses a 12-hour time string (e.g. "11:30 AM", "2:00 PM") into minutes from midnight (0..1439).
 * Returns null if the format is invalid.
 */
export function parseTimeSlotToMinutes(slot?: string | null): number | null {
  if (!slot || typeof slot !== 'string') return null;
  const match = slot.trim().match(/^(0?[1-9]|1[0-2]):([0-5]\d)\s*(AM|PM|am|pm)$/);
  if (!match) return null;
  let hour = parseInt(match[1], 10);
  const minute = parseInt(match[2], 10);
  const period = match[3].toUpperCase();
  if (period === 'PM' && hour !== 12) hour += 12;
  if (period === 'AM' && hour === 12) hour = 0;
  return hour * 60 + minute;
}

/**
 * Validates whether a pickup date string is a real calendar date, not in the past,
 * and within the maximum advance booking limit.
 */
export function isPickupDateValid(dateStr?: string | null, referenceDate: Date = new Date()): boolean {
  if (!dateStr || typeof dateStr !== 'string') return false;
  const trimmed = dateStr.trim();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(trimmed)) return false;

  const parts = trimmed.split('-').map(p => parseInt(p, 10));
  const year = parts[0];
  const month = parts[1];
  const day = parts[2];
  if (isNaN(year) || isNaN(month) || isNaN(day)) return false;

  const parsed = new Date(year, month - 1, day, 12, 0, 0);
  if (
    parsed.getFullYear() !== year ||
    parsed.getMonth() !== month - 1 ||
    parsed.getDate() !== day
  ) {
    return false;
  }

  const todayStr = getRestaurantTodayDateString(referenceDate);
  if (trimmed < todayStr) {
    return false;
  }

  const todayParts = todayStr.split('-').map(p => parseInt(p, 10));
  const todayDate = new Date(todayParts[0], todayParts[1] - 1, todayParts[2], 12, 0, 0);
  const diffDays = Math.round((parsed.getTime() - todayDate.getTime()) / (1000 * 60 * 60 * 24));
  if (diffDays > MAX_PICKUP_ADVANCE_DAYS) {
    return false;
  }

  return true;
}

/**
 * Checks whether a specific time slot has already passed for a given date.
 */
export function isTimeSlotInPast(
  dateStr?: string | null,
  slot?: string | null,
  referenceDate: Date = new Date()
): boolean {
  if (!dateStr || !slot) return false;
  const todayStr = getRestaurantTodayDateString(referenceDate);
  if (dateStr !== todayStr) return false;

  const nowParts = getRestaurantNowParts(referenceDate);
  const nowMinutes = nowParts.hour * 60 + nowParts.minute;

  if (slot.trim().toLowerCase() === 'asap') {
    return nowMinutes > CLOSING_HOUR * 60;
  }

  const slotMinutes = parseTimeSlotToMinutes(slot);
  if (slotMinutes === null) return true;
  return slotMinutes <= nowMinutes;
}

/**
 * Validates whether a pickup time selection is valid for the given pickup date.
 * Rejects unrecognized slots, times outside 11:00 AM - 9:00 PM, and slots in the past for today.
 */
export function isPickupTimeValid(
  dateStr?: string | null,
  timeStr?: string | null,
  referenceDate: Date = new Date()
): boolean {
  if (!timeStr || typeof timeStr !== 'string') return false;
  const trimmedTime = timeStr.trim();
  if (!trimmedTime) return false;

  if (!isPickupDateValid(dateStr, referenceDate)) {
    return false;
  }

  const todayStr = getRestaurantTodayDateString(referenceDate);
  const nowParts = getRestaurantNowParts(referenceDate);
  const nowMinutes = nowParts.hour * 60 + nowParts.minute;
  const closingMinutes = CLOSING_HOUR * 60; // 21:00

  const isToday = dateStr === todayStr;

  if (trimmedTime.toLowerCase() === 'asap') {
    if (isToday) {
      return nowMinutes <= closingMinutes;
    }
    return true;
  }

  const slotMinutes = parseTimeSlotToMinutes(trimmedTime);
  if (slotMinutes === null) return false;

  const openingMinutes = OPENING_HOUR * 60; // 11:00
  if (slotMinutes < openingMinutes || slotMinutes > closingMinutes) {
    return false;
  }
  if (slotMinutes % 30 !== 0) {
    return false;
  }

  if (isToday) {
    return slotMinutes > nowMinutes;
  }

  return true;
}

export interface PickupSelections {
  pickupDate: string;
  pickupTime: string;
}

/**
 * Reads pickup selections from storage, validating that neither the date nor the time
 * has become stale, expired, or invalid. Stale/invalid entries are automatically cleaned up.
 */
export function loadPickupSelections(
  storage?: Pick<Storage, 'getItem' | 'removeItem'>,
  referenceDate: Date = new Date()
): PickupSelections {
  const store = storage ?? (typeof window !== 'undefined' ? window.sessionStorage : undefined);
  if (!store) {
    return { pickupDate: '', pickupTime: '' };
  }

  const rawDate = store.getItem(PICKUP_DATE_STORAGE_KEY) || '';
  const rawTime = store.getItem(PICKUP_TIME_STORAGE_KEY) || '';

  const dateValid = isPickupDateValid(rawDate, referenceDate);
  if (!dateValid) {
    if (rawDate) store.removeItem(PICKUP_DATE_STORAGE_KEY);
    if (rawTime) store.removeItem(PICKUP_TIME_STORAGE_KEY);
    return { pickupDate: '', pickupTime: '' };
  }

  const timeValid = isPickupTimeValid(rawDate, rawTime, referenceDate);
  if (!timeValid) {
    if (rawTime) store.removeItem(PICKUP_TIME_STORAGE_KEY);
    return { pickupDate: rawDate, pickupTime: '' };
  }

  return { pickupDate: rawDate, pickupTime: rawTime };
}

/**
 * Persists pickup date to storage, removing the key if empty.
 */
export function savePickupDate(
  value: string,
  storage?: Pick<Storage, 'setItem' | 'removeItem'>
): void {
  const store = storage ?? (typeof window !== 'undefined' ? window.sessionStorage : undefined);
  if (!store) return;
  if (value && value.trim()) {
    store.setItem(PICKUP_DATE_STORAGE_KEY, value.trim());
  } else {
    store.removeItem(PICKUP_DATE_STORAGE_KEY);
  }
}

/**
 * Persists pickup time to storage, removing the key if empty.
 */
export function savePickupTime(
  value: string,
  storage?: Pick<Storage, 'setItem' | 'removeItem'>
): void {
  const store = storage ?? (typeof window !== 'undefined' ? window.sessionStorage : undefined);
  if (!store) return;
  if (value && value.trim()) {
    store.setItem(PICKUP_TIME_STORAGE_KEY, value.trim());
  } else {
    store.removeItem(PICKUP_TIME_STORAGE_KEY);
  }
}

/**
 * Clears stored pickup selections completely (called after completed order).
 */
export function clearPickupSelections(
  storage?: Pick<Storage, 'removeItem'>
): void {
  const store = storage ?? (typeof window !== 'undefined' ? window.sessionStorage : undefined);
  if (!store) return;
  store.removeItem(PICKUP_DATE_STORAGE_KEY);
  store.removeItem(PICKUP_TIME_STORAGE_KEY);
}
